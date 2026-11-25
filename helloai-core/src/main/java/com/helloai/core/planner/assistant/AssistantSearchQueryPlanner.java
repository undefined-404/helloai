package com.helloai.core.planner.assistant;

import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.clarify.AttachmentIntent;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser.ChatRoundDecision;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.ClarifyPromptRenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 助理本轮联网检索决策：结合最近对话、上下文摘要、附件决策视图与系统时间，
 * 产出一个可直接检索的独立查询词，并判定附件是否已能独立回答。
 *
 * <p>与需求澄清链路的联合决策同源：复用
 * {@link ClarifyPromptRenderService#renderDecisionPrompt} 渲染的 Prompt（历史 +
 * 摘要 + 附件视图 + 时间 + 搜索策略）、{@link ChatRoundDecisionParser} 的严格解析、
 * 以及同一条平台执行链（Planner Agent + {@code executeSync}）。差异只在场景约束：
 * 本组件不生成用户可见回复，只取 {@code web_search.search_query} 与
 * {@code attachment_intent}。</p>
 *
 * <p>失败语义：渲染 / 调用 / 解析任一环节失败都降级为「无优先词、不跳过检索」，
 * 由调用方按现有规则词检索继续，不阻断对话。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantSearchQueryPlanner {

    /** 检索决策 AgentTask 的 scene 标识（日志可观测 / 测试 stub 精确匹配）。 */
    public static final String SCENE = "assistant_search_query";

    /** 追加在通用决策 Prompt 末尾的场景约束：不改模板、不改渲染接口。 */
    private static final String SCENE_CONSTRAINT = """

            ## 场景约束（助理检索，必须遵守）
            - 本调用只用于助理本轮联网检索决策；intent 必须为 chat，clarification_question 必须为 null。
            - **先判断数据来源，再决定是否联网**：问题若可由「平台内数据」回答（用户自己的任务 / 子任务 /
              执行与核验记录 / 历史对话 / 个人记忆 / 工作台概况），web_search.need_search 必须为 false，
              search_query 置 null，reason 写 internal_data。
            - 只有依赖平台外、时效性事实（新闻 / 价格 / 官网 / 外部文档 / 公开资料）时才 need_search=true，
              并给出可直接检索的独立关键词；此时 search_query 不得为空。
            - 历史中的「这次 / 上面 / 刚才」等指代必须结合历史改写为独立主题；相对时间转绝对日期。
            - 若附件已能完整回答，attachment_intent 设为 answerable；否则按 needs_external / none 判定。
            - **用户点名了「哪几份附件」时，必须把序号填进 attachment_targets**（1-based，对应
              「本轮附件」清单里的 `附件N`）；只说「附件」不指向具体某份时填 `[]`。
              这是本链唯一的附件作用域信号——漏填就意味着会话里全部附件的正文都会进来。
            """;

    /** 平台内数据类问题的意图标记（进 plan.attachmentIntent，供调用方选择提示文案）。 */
    public static final String INTERNAL_DATA = "internal_data";

    /** 强「平台内数据」信号：自身任务/子任务/核验/历史/记忆/进度。 */
    private static final Pattern INTERNAL_INTENT = Pattern.compile(
            "(子任务|任务进度|任务状态|任务情况|我的任务|执行记录|核验|审查记录|评审|返工|"
                    + "上次对话|之前的对话|对话历史|记忆|在测什么|最近在测|测些什么|进展)");

    /** 外部时效信号：出现任一项就不走规则短路，交 LLM 决策（宁可多搜，不误跳过）。 */
    private static final Pattern EXTERNAL_INTENT = Pattern.compile(
            "(最新|新闻|价格|官网|实时|今天|本周|发布|教程|文档|联网|搜索)");

    private final ClarifyPromptRenderService promptRenderService;
    private final ChatRoundDecisionParser decisionParser;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final WebSearchProperties properties;

    /**
     * 生成本轮检索计划。
     *
     * <p><b>为什么需要 {@code searchAllowed}</b>（`G-043`）：本方法一次调用同时产出
     * <b>两个互不相关</b>的结论——「本轮附件作用域是哪些」与「要不要联网」。原实现把整个
     * 调用包在 {@code conversation.webSearchEnabled} 门控里，等于让「不联网」顺带否决
     * 「判定附件作用域」。而工作台默认 {@code webSearchEnabled=false}，于是
     * {@code attachment_targets} <b>在生产默认配置下永远不会被解析</b>——用户说
     * 「就按第 2 张图来」，后端只知道「该看附件」，不知道「看哪几张」。
     * 拆出本参数后，开关只否决联网这一个结论，附件作用域照常产出。</p>
     *
     * @param conversation   助理会话（提供历史、附件与 Planner 选择）
     * @param userMessage    本轮用户原话
     * @param hasAttachments 本会话是否挂了附件；仅此时 {@code answerable} 才允许跳过联网
     * @param searchAllowed  本会话是否允许真正发起联网检索（{@code webSearchEnabled}）；
     *                       为 {@code false} 时 {@code skipSearch} 恒真，但附件作用域照常返回
     */
    public AssistantSearchPlan plan(RequirementConversation conversation, String userMessage,
                                    boolean hasAttachments, boolean searchAllowed) {
        if (conversation == null || !properties.isEnabled()) {
            return AssistantSearchPlan.disabledPlan();
        }
        // 不联网 + 无附件 ⇒ 「要不要搜」与「看哪几份附件」两个结论都已经确定，
        // 没有为它们花一次决策 LLM 调用的理由。
        if (!searchAllowed && !hasAttachments) {
            return AssistantSearchPlan.disabledPlan();
        }
        // 先按确定性规则分诊：明确指向「平台内数据」的问题直接跳过联网，不花一次决策 LLM 调用。
        // 只认强信号且不含外部时效词，命中才短路；边界情况仍交给下面的 LLM 决策（宁多不误跳）。
        // 不联网时不必分诊「要不要搜」，但仍要跑决策拿附件作用域 ⇒ 加 searchAllowed 门控。
        if (searchAllowed && looksLikeInternalDataQuestion(userMessage)) {
            log.info("助理检索决策: conversationId={}, skipSearch=true（平台内数据，规则短路）", conversation.getId());
            return new AssistantSearchPlan(null, true, false, INTERNAL_DATA, List.of());
        }
        long t0 = System.currentTimeMillis();
        try {
            AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
            AgentTask task = AgentTask.builder()
                    .systemPrompt("")
                    .userPrompt(promptRenderService.renderDecisionPrompt(conversation, userMessage)
                            + SCENE_CONSTRAINT)
                    .context(Map.of("conversationId", conversation.getId(), "scene", SCENE))
                    .requiredCapabilities(Map.of())
                    .build();
            AgentResult result = platformAgentExecutionService.executeSync(planner.id(), task);
            if (result == null || !result.isSuccess()) {
                log.warn("助理检索决策失败（降级规则检索）: conversationId={}, err={}",
                        conversation.getId(), result == null ? "空响应" : result.getErrorMessage());
                return AssistantSearchPlan.degradedPlan();
            }
            ChatRoundDecision decision = decisionParser.parse(result.getOutput());
            // need_search=false ⇒ 模型判断本轮无需联网（平台内数据可回答）；搜索词一律忽略
            boolean needSearch = decision.webSearch() != null && decision.webSearch().needSearch();
            String query = needSearch && decision.webSearch() != null ? decision.webSearch().searchQuery() : null;
            if (query != null && query.isBlank()) {
                query = null;
            }
            boolean answerable = decision.attachmentIntent() == AttachmentIntent.ANSWERABLE;
            // 不联网时恒跳过：skipSearch 的语义是「本轮不发起检索」，不是「检索无用」。
            boolean skipSearch = !searchAllowed || !needSearch || (answerable && hasAttachments);
            String attachmentIntent = decision.attachmentIntent() == null
                    ? "unknown" : decision.attachmentIntent().wireValue();
            // `G-043`：作用域信号必须与 intent 一起流出——只取 intent 丢掉 targets，
            // 就会出现「知道该看附件、但不知道看哪几份」⇒ 只能全量注入。
            // 越界序号由下游 `resolveScope` 再兜一次（那里知道实际附件数）。
            List<Integer> attachmentTargets = decision.attachmentTargets() == null
                    ? List.of() : decision.attachmentTargets();
            log.info("助理检索决策: conversationId={}, query={}, attachmentIntent={}, attachmentTargets={}, "
                            + "skipSearch={}, costMs={}",
                    conversation.getId(), query, attachmentIntent, attachmentTargets, skipSearch,
                    System.currentTimeMillis() - t0);
            return new AssistantSearchPlan(query, skipSearch, false, attachmentIntent, attachmentTargets);
        } catch (Exception e) {
            log.warn("助理检索决策失败（降级规则检索）: conversationId={}, err={}",
                    conversation.getId(), e.getMessage());
            return AssistantSearchPlan.degradedPlan();
        }
    }

    /** 规则分诊：明确指向平台内数据、且不含外部时效词时才返回 true。 */
    static boolean looksLikeInternalDataQuestion(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return INTERNAL_INTENT.matcher(userMessage).find()
                && !EXTERNAL_INTENT.matcher(userMessage).find();
    }

    /**
     * 本轮检索计划。
     *
     * @param priorityQuery     LLM 优化搜索词；null/空白表示走规则词
     * @param skipSearch        附件已能独立回答且确有计划附件时为 true
     * @param degraded          决策失败，已降级为规则检索
     * @param attachmentIntent  附件意图（仅日志与排障）
     * @param attachmentTargets 本轮指向的附件序号（1-based，与清单一致）；null/空 = 不限。
     *                          <p><b>`G-043`</b>：此前本 record 只有上面四个字段，
     *                          {@code attachment_targets} <b>整条无解析</b>——工作台里用户说
     *                          「就按第 2 张图来」不生效，只能全量注入。这不是前端没做，
     *                          是<b>后端没接</b>：决策 LLM 明明输出了该字段
     *                          （{@code ChatRoundDecisionParser} 一直在解析），但助理链
     *                          拿到 {@link ChatRoundDecision} 后只取了 {@code attachmentIntent}，
     *                          把作用域信号整个丢掉了。</p>
     *                          <p><b>为什么放在本 record 而不是另开一个参数</b>：附件作用域是
     *                          「本轮决策」的一部分，必须与 {@code attachmentIntent} 同生命周期
     *                          一起流到渲染点；拆成两个参数就会出现「intent 生效了、targets 忘了传」
     *                          这种只有一半作用域的状态。</p>
     */
    public record AssistantSearchPlan(String priorityQuery, boolean skipSearch, boolean degraded,
                                      String attachmentIntent, List<Integer> attachmentTargets) {

        /** 未启用 / 开关关闭：不调用 LLM，也不跳过规则检索。 */
        public static AssistantSearchPlan disabledPlan() {
            return new AssistantSearchPlan(null, false, false, "disabled", List.of());
        }

        /** 决策失败：无优先词、不跳过检索，行为与改造前一致。 */
        public static AssistantSearchPlan degradedPlan() {
            return new AssistantSearchPlan(null, false, true, "degraded", List.of());
        }
    }
}
