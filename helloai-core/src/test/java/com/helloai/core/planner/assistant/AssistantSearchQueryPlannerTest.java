package com.helloai.core.planner.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.assistant.AssistantSearchQueryPlanner.AssistantSearchPlan;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.ClarifyPromptRenderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AssistantSearchQueryPlanner} 单测：复用决策 Prompt 的检索词透传、
 * answerable 跳过联网的附件前置条件、失败降级与开关关闭零调用。
 */
@DisplayName("AssistantSearchQueryPlanner")
class AssistantSearchQueryPlannerTest {

    private static final Long CONVERSATION_ID = 100L;
    private static final String MESSAGE = "最近的 AI 相关新闻，这次联网查询后，展开详细说说吧";

    private ClarifyPromptRenderService promptRenderService;
    private PlatformAgentExecutionService platformAgentExecutionService;
    private PlannerAgentPicker plannerAgentPicker;
    private AssistantSearchQueryPlanner planner;
    private RequirementConversation conversation;

    @BeforeEach
    void setUp() {
        promptRenderService = mock(ClarifyPromptRenderService.class);
        platformAgentExecutionService = mock(PlatformAgentExecutionService.class);
        plannerAgentPicker = mock(PlannerAgentPicker.class);
        planner = new AssistantSearchQueryPlanner(promptRenderService,
                new ChatRoundDecisionParser(new ObjectMapper()),
                platformAgentExecutionService, plannerAgentPicker, new WebSearchProperties());

        conversation = new RequirementConversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setWebSearchEnabled(true);

        AgentProfileSnapshot snapshot = mock(AgentProfileSnapshot.class);
        when(plannerAgentPicker.pick(isNull())).thenReturn(snapshot);
        when(snapshot.id()).thenReturn(1L);
    }

    @Test
    @DisplayName("正常决策：透传 LLM 搜索词，不跳过联网")
    void plan_normalDecision_returnsPriorityQuery() {
        when(promptRenderService.renderDecisionPrompt(conversation, MESSAGE)).thenReturn("DECISION_PROMPT");
        when(platformAgentExecutionService.executeSync(eq(1L), any(AgentTask.class)))
                .thenReturn(AgentResult.success(decisionJson("none", "AI 行业 最新动态"), "stop", "llm", 100));

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, false, true);

        assertThat(plan.priorityQuery()).isEqualTo("AI 行业 最新动态");
        assertThat(plan.skipSearch()).isFalse();
        assertThat(plan.degraded()).isFalse();
        assertThat(plan.attachmentIntent()).isEqualTo("none");

        // 场景约束追加在通用决策 Prompt 末尾（不改模板本身）
        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(eq(1L), taskCaptor.capture());
        assertThat(taskCaptor.getValue().getUserPrompt())
                .startsWith("DECISION_PROMPT")
                .contains("场景约束（助理检索，必须遵守）");
    }

    @Test
    @DisplayName("附件 answerable 且确有附件：跳过联网")
    void plan_answerableWithAttachments_skipsSearch() {
        when(promptRenderService.renderDecisionPrompt(conversation, MESSAGE)).thenReturn("P");
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(decisionJson("answerable", "手册 第3章"), "stop", "llm", 100));

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, true, true);

        assertThat(plan.skipSearch()).isTrue();
        assertThat(plan.attachmentIntent()).isEqualTo("answerable");
        assertThat(plan.degraded()).isFalse();
    }

    @Test
    @DisplayName("附件 answerable 但会话无附件：不跳过（避免误跳过联网）")
    void plan_answerableWithoutAttachments_doesNotSkip() {
        when(promptRenderService.renderDecisionPrompt(conversation, MESSAGE)).thenReturn("P");
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(decisionJson("answerable", "手册 第3章"), "stop", "llm", 100));

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, false, true);

        assertThat(plan.skipSearch()).isFalse();
    }

    @Test
    @DisplayName("平台内数据类问题（子任务/进度）：规则短路，不调用决策 LLM、不联网")
    void plan_internalDataQuestion_skipsWithoutLlm() {
        AssistantSearchPlan plan = planner.plan(conversation,
                "最近的子任务都在做些什么？再测些什么？", false, true);

        assertThat(plan.skipSearch()).isTrue();
        assertThat(plan.attachmentIntent()).isEqualTo(AssistantSearchQueryPlanner.INTERNAL_DATA);
        assertThat(plan.priorityQuery()).isNull();
        verifyNoInteractions(promptRenderService, platformAgentExecutionService, plannerAgentPicker);
    }

    @Test
    @DisplayName("LLM 判定 need_search=false：跳过联网（平台内数据可回答）")
    void plan_llmSaysNoSearch_skips() {
        when(promptRenderService.renderDecisionPrompt(conversation, MESSAGE)).thenReturn("P");
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(decisionJson("none", null, false), "stop", "llm", 100));

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, false, true);

        assertThat(plan.skipSearch()).isTrue();
        assertThat(plan.priorityQuery()).isNull();
    }

    @Test
    @DisplayName("决策调用异常：降级为规则检索，不抛异常")
    void plan_llmFailure_degrades() {
        when(promptRenderService.renderDecisionPrompt(conversation, MESSAGE)).thenReturn("P");
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenThrow(new RuntimeException("LLM 不可用"));

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, false, true);

        assertThat(plan.degraded()).isTrue();
        assertThat(plan.priorityQuery()).isNull();
        assertThat(plan.skipSearch()).isFalse();
    }

    @Test
    @DisplayName("G-043：开关关闭但无附件 ⇒ 两个结论都已确定，不调用决策 LLM")
    void plan_disabledWithoutAttachments_skipsLlm() {
        conversation.setWebSearchEnabled(false);

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, false, false);

        assertThat(plan.degraded()).isFalse();
        assertThat(plan.skipSearch()).isFalse();
        assertThat(plan.attachmentIntent()).isEqualTo("disabled");
        verifyNoInteractions(promptRenderService, platformAgentExecutionService, plannerAgentPicker);
    }

    // ══════════════════════════════════════════════════════════════
    //  `G-043`：attachment_targets 必须解析出来，且不能被「不联网」顺带否决
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G-043：LLM 输出的 attachment_targets 必须透传到 plan（原实现整条丢弃）")
    void plan_attachmentTargets_arePropagated() {
        when(promptRenderService.renderDecisionPrompt(conversation, MESSAGE)).thenReturn("P");
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        decisionJson("answerable", "第2张图", new int[]{2}), "stop", "llm", 100));

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, true, true);

        assertThat(plan.attachmentTargets()).containsExactly(2);
    }

    @Test
    @DisplayName("G-043：不联网（webSearchEnabled=false）时仍须解析 attachment_targets")
    void plan_searchDisabled_stillResolvesTargets() {
        conversation.setWebSearchEnabled(false);
        when(promptRenderService.renderDecisionPrompt(conversation, MESSAGE)).thenReturn("P");
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        decisionJson("answerable", "第3张图", new int[]{3}), "stop", "llm", 100));

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, true, false);

        assertThat(plan.attachmentIntent()).isEqualTo("answerable");
        assertThat(plan.attachmentTargets()).containsExactly(3);
        // 不联网 ⇒ skipSearch 恒真（语义是「本轮不发起检索」）
        assertThat(plan.skipSearch()).isTrue();
        assertThat(plan.degraded()).isFalse();
    }

    @Test
    @DisplayName("G-043：不联网且附件为空 ⇒ 两个结论都已确定，不花一次决策调用")
    void plan_searchDisabledWithoutAttachments_skipsLlm() {
        conversation.setWebSearchEnabled(false);

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, false, false);

        assertThat(plan.attachmentIntent()).isEqualTo("disabled");
        assertThat(plan.attachmentTargets()).isEmpty();
        verifyNoInteractions(promptRenderService, platformAgentExecutionService, plannerAgentPicker);
    }

    @Test
    @DisplayName("G-043：不联网时不做「平台内数据」规则短路——仍需跑决策拿附件作用域")
    void plan_searchDisabled_doesNotShortCircuitInternalData() {
        conversation.setWebSearchEnabled(false);
        when(promptRenderService.renderDecisionPrompt(conversation, MESSAGE)).thenReturn("P");
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        decisionJson("answerable", "第1张图", new int[]{1}), "stop", "llm", 100));

        AssistantSearchPlan plan = planner.plan(conversation,
                "看看我的子任务进度，顺便说下第1张图", true, false);

        // 若被规则短路，attachmentTargets 会丢（INTERNAL_DATA 分支返回空表）
        assertThat(plan.attachmentIntent()).isEqualTo("answerable");
        assertThat(plan.attachmentTargets()).containsExactly(1);
    }

    private static String decisionJson(String attachmentIntent, String query) {
        return decisionJson(attachmentIntent, query, true);
    }

    private static String decisionJson(String attachmentIntent, String query, boolean needSearch) {
        return decisionJson(attachmentIntent, query, needSearch, new int[0]);
    }

    private static String decisionJson(String attachmentIntent, String query, int[] targets) {
        return decisionJson(attachmentIntent, query, true, targets);
    }

    private static String decisionJson(String attachmentIntent, String query, boolean needSearch,
                                       int[] targets) {
        String queryJson = query == null ? "null" : "\"" + query + "\"";
        StringBuilder targetsJson = new StringBuilder("[");
        for (int i = 0; i < targets.length; i++) {
            if (i > 0) targetsJson.append(',');
            targetsJson.append(targets[i]);
        }
        targetsJson.append(']');
        return "{\"intent\":\"chat\",\"intent_reason\":\"direct_answer\","
                + "\"clarification_question\":null,"
                + "\"attachment_intent\":\"" + attachmentIntent + "\","
                + "\"attachment_targets\":" + targetsJson + ","
                + "\"web_search\":{\"need_search\":" + needSearch + ",\"search_query\":" + queryJson
                + ",\"reason\":\"时效性\"}}";
    }
}
