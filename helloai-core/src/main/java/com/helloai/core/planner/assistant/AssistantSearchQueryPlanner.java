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
            - 搜索开关已开启，web_search.need_search 必须为 true，search_query 必须是非空、可直接检索的独立关键词。
            - 历史中的「这次 / 上面 / 刚才」等指代必须结合历史改写为独立主题；相对时间转绝对日期。
            - 若附件已能完整回答，attachment_intent 设为 answerable；否则按 needs_external / none 判定。
            """;

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
            String query = decision.webSearch() == null ? null : decision.webSearch().searchQuery();
            if (query != null && query.isBlank()) {
                query = null;
            }
            boolean answerable = decision.attachmentIntent() == AttachmentIntent.ANSWERABLE;
            boolean skipSearch = answerable && hasAttachments;
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
