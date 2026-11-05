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
     * @param conversation   助理会话（提供历史、附件与 Planner 选择）
     * @param userMessage    本轮用户原话
     * @param hasAttachments 本会话是否挂了附件；仅此时 {@code answerable} 才允许跳过联网
     */
    public AssistantSearchPlan plan(RequirementConversation conversation, String userMessage,
                                    boolean hasAttachments) {
        if (conversation == null || !properties.isEnabled()
                || !Boolean.TRUE.equals(conversation.getWebSearchEnabled())) {
            return AssistantSearchPlan.disabledPlan();
        }
        // 先按确定性规则分诊：明确指向「平台内数据」的问题直接跳过联网，不花一次决策 LLM 调用。
        // 只认强信号且不含外部时效词，命中才短路；边界情况仍交给下面的 LLM 决策（宁多不误跳）。
        if (looksLikeInternalDataQuestion(userMessage)) {
            log.info("助理检索决策: conversationId={}, skipSearch=true（平台内数据，规则短路）", conversation.getId());
            return new AssistantSearchPlan(null, true, false, INTERNAL_DATA);
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
            boolean skipSearch = !needSearch || (answerable && hasAttachments);
            String attachmentIntent = decision.attachmentIntent() == null
                    ? "unknown" : decision.attachmentIntent().wireValue();
            log.info("助理检索决策: conversationId={}, query={}, attachmentIntent={}, skipSearch={}, costMs={}",
                    conversation.getId(), query, attachmentIntent, skipSearch,
                    System.currentTimeMillis() - t0);
            return new AssistantSearchPlan(query, skipSearch, false, attachmentIntent);
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
     */
    public record AssistantSearchPlan(String priorityQuery, boolean skipSearch, boolean degraded,
                                      String attachmentIntent) {

        /** 未启用 / 开关关闭：不调用 LLM，也不跳过规则检索。 */
        public static AssistantSearchPlan disabledPlan() {
            return new AssistantSearchPlan(null, false, false, "disabled");
        }

        /** 决策失败：无优先词、不跳过检索，行为与改造前一致。 */
        public static AssistantSearchPlan degradedPlan() {
            return new AssistantSearchPlan(null, false, true, "degraded");
        }
    }
}
