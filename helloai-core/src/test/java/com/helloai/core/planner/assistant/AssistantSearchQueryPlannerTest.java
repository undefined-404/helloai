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

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, false);

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

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, true);

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

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, false);

        assertThat(plan.skipSearch()).isFalse();
    }

    @Test
    @DisplayName("决策调用异常：降级为规则检索，不抛异常")
    void plan_llmFailure_degrades() {
        when(promptRenderService.renderDecisionPrompt(conversation, MESSAGE)).thenReturn("P");
        when(platformAgentExecutionService.executeSync(anyLong(), any(AgentTask.class)))
                .thenThrow(new RuntimeException("LLM 不可用"));

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, false);

        assertThat(plan.degraded()).isTrue();
        assertThat(plan.priorityQuery()).isNull();
        assertThat(plan.skipSearch()).isFalse();
    }

    @Test
    @DisplayName("会话开关关闭：不调用决策 LLM")
    void plan_disabled_skipsLlm() {
        conversation.setWebSearchEnabled(false);

        AssistantSearchPlan plan = planner.plan(conversation, MESSAGE, true);

        assertThat(plan.degraded()).isFalse();
        assertThat(plan.skipSearch()).isFalse();
        assertThat(plan.attachmentIntent()).isEqualTo("disabled");
        verifyNoInteractions(promptRenderService, platformAgentExecutionService, plannerAgentPicker);
    }

    private static String decisionJson(String attachmentIntent, String query) {
        return "{\"intent\":\"chat\",\"intent_reason\":\"direct_answer\","
                + "\"clarification_question\":null,"
                + "\"attachment_intent\":\"" + attachmentIntent + "\","
                + "\"attachment_targets\":[],"
                + "\"web_search\":{\"need_search\":true,\"search_query\":\"" + query
                + "\",\"reason\":\"时效性\"}}";
    }
}
