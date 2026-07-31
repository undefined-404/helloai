package com.helloai.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.execution.PlatformAgentExecutionService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RequirementClarifyService 单元测试（LLM mock）：
 * question/final 双路径 / fence 容错 / 非 JSON 报错 / 轮数上限 /
 * finalize 无终稿拒绝、成功建任务 / 非 ACTIVE 会话拒发 /
 * create 手动指定 Planner / retry 重试上一轮。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RequirementClarifyService")
class RequirementClarifyServiceTest {

    private static final Long CONV_ID = 200L;

    @Mock
    private RequirementConversationService conversationService;

    @Mock
    private RequirementMessageService messageService;

    @Mock
    private TaskService taskService;

    @Mock
    private AgentService agentService;

    @Mock
    private PlannerAgentPicker plannerAgentPicker;

    @Mock
    private AgentInboxService agentInboxService;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    @Mock
    private TaskTimelineService taskTimelineService;

    private RequirementClarifyService clarifyService;

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）
        clarifyService = new RequirementClarifyService(
                conversationService, messageService, taskService, agentService,
                plannerAgentPicker, agentInboxService, platformAgentExecutionService,
                taskTimelineService, new ObjectMapper());
    }

    private RequirementConversation activeConversation() {
        RequirementConversation conversation = new RequirementConversation();
        conversation.setId(CONV_ID);
        conversation.setTitle("做一个报表");
        conversation.setStatus(RequirementClarifyService.STATUS_ACTIVE);
        conversation.setRoundCount(1);
        return conversation;
    }

    private Agent llmPlanner() {
        Agent agent = new Agent();
        agent.setId(9L);
        agent.setName("planner-llm");
        agent.setRole(AgentRole.PLANNER);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        return agent;
    }

    private RequirementMessage message(String role, String content, int seq) {
        RequirementMessage msg = new RequirementMessage();
        msg.setConversationId(CONV_ID);
        msg.setRole(role);
        msg.setContent(content);
        msg.setSeq(seq);
        return msg;
    }

    /** 打通一轮 LLM 调用所需的公共 stub（未钉 Planner：自动选择）。 */
    private void stubLlmRound(String rawOutput) {
        when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
        when(messageService.listByConversation(CONV_ID))
                .thenReturn(List.of(message("user", "做一个报表", 1)));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(rawOutput, "stop", "llm", 100));
    }

    // ══════════════════════════════════════════════════════════════
    //  sendMessage：question / final 双路径
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("question 路径：markdown fence 容错解析，assistant 消息落库，round_count+1")
    void shouldHandleQuestionReplyWithFence() {
        RequirementConversation conversation = activeConversation();
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        stubLlmRound("""
                ```json
                {"type":"question","message":"请问验收标准是什么？"}
                ```
                """);

        RequirementClarifyService.ClarifyConversationDetail detail =
                clarifyService.sendMessage(CONV_ID, "做一个报表");

        assertThat(detail.getConversation().getRoundCount()).isEqualTo(2);
        assertThat(detail.getConversation().getFinalTitle()).isNull();
        verify(messageService).addMessage(CONV_ID, "user", "做一个报表", null);
        verify(messageService).addMessage(CONV_ID, "assistant", "请问验收标准是什么？", null);
        verify(conversationService, org.mockito.Mockito.times(1)).updateById(conversation);
    }

    @Test
    @DisplayName("structured 追问：校验通过后 payload 落库，content 合成可读文本（V33）")
    void shouldHandleStructuredQuestionReply() {
        when(conversationService.getById(CONV_ID)).thenReturn(activeConversation());
        stubLlmRound("{\"type\":\"question\",\"mode\":\"structured\",\"progress\":40,"
                + "\"message\":\"帮我确认两点\",\"questions\":[{\"id\":\"q1\",\"text\":\"给谁用？\","
                + "\"multiple\":false,\"allowCustom\":true,\"options\":["
                + "{\"label\":\"内部员工\",\"value\":\"opt_a\",\"recommended\":true},"
                + "{\"label\":\"外部客户\",\"value\":\"opt_b\"}]}]}");

        clarifyService.sendMessage(CONV_ID, "做一个报表");

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                contentCaptor.capture(), payloadCaptor.capture());
        assertThat(contentCaptor.getValue())
                .contains("帮我确认两点").contains("给谁用？").contains("内部员工");
        assertThat(payloadCaptor.getValue())
                .contains("\"mode\":\"structured\"")
                .contains("\"progress\":40")
                .contains("\"questions\"");
    }

    @Test
    @DisplayName("structured 校验失败（选项为空）：降级 freeform，questions 丢弃（V33）")
    void shouldDowngradeInvalidStructuredToFreeform() {
        when(conversationService.getById(CONV_ID)).thenReturn(activeConversation());
        stubLlmRound("{\"type\":\"question\",\"mode\":\"structured\",\"progress\":30,"
                + "\"message\":\"你的目标是什么？\","
                + "\"questions\":[{\"id\":\"q1\",\"text\":\"目标？\",\"options\":[]}]}");

        clarifyService.sendMessage(CONV_ID, "做一个报表");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                eq("你的目标是什么？"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .contains("\"mode\":\"freeform\"")
                .doesNotContain("questions");
    }

    @Test
    @DisplayName("sendMessage 附选项快照：user 消息 payload 存 selections（V33）")
    void shouldPersistSelectionSnapshotOnUserMessage() {
        when(conversationService.getById(CONV_ID)).thenReturn(activeConversation());
        stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

        RequirementClarifyService.ClarifySelection selection =
                new RequirementClarifyService.ClarifySelection();
        selection.setQuestionId("q1");
        selection.setQuestionText("给谁用？");
        selection.setValues(List.of("opt_a"));
        selection.setLabels(List.of("内部员工"));

        clarifyService.sendMessage(CONV_ID, "给谁用？：内部员工", List.of(selection));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(CONV_ID), eq("user"),
                eq("给谁用？：内部员工"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .contains("\"selections\"").contains("q1").contains("内部员工");
    }

    @Test
    @DisplayName("final 路径：终稿写入 final_title/final_description，assistant 消息为终稿说明")
    void shouldHandleFinalReply() {
        RequirementConversation conversation = activeConversation();
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        stubLlmRound("{\"type\":\"final\",\"title\":\"搭建日报模块\","
                + "\"description\":\"## 背景\\n做日报\",\"message\":\"需求已清晰\"}");

        clarifyService.sendMessage(CONV_ID, "没有其他要求了");

        assertThat(conversation.getFinalTitle()).isEqualTo("搭建日报模块");
        assertThat(conversation.getFinalDescription()).isEqualTo("## 背景\n做日报");
        verify(messageService).addMessage(CONV_ID, "assistant", "需求已清晰");
        // round_count+1 与终稿回填各一次 updateById
        verify(conversationService, org.mockito.Mockito.times(2)).updateById(conversation);
    }

    @Test
    @DisplayName("final 路径：message 为空时 assistant 消息兜底「已生成终稿」")
    void shouldFallbackFinalNoteWhenMessageBlank() {
        RequirementConversation conversation = activeConversation();
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        stubLlmRound("{\"type\":\"final\",\"title\":\"标题\",\"description\":\"描述\"}");

        clarifyService.sendMessage(CONV_ID, "直接生成吧");

        verify(messageService).addMessage(CONV_ID, "assistant", "已生成终稿");
    }

    // ══════════════════════════════════════════════════════════════
    //  sendMessage：失败与拒绝路径
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("非 JSON 且不含 type 字样：降级为 freeform 追问落库，不报错（V33）")
    void shouldDegradeToFreeformWhenOutputIsNotJson() {
        when(conversationService.getById(CONV_ID)).thenReturn(activeConversation());
        stubLlmRound("抱歉，我无法帮你澄清。");

        RequirementClarifyService.ClarifyConversationDetail detail =
                clarifyService.sendMessage(CONV_ID, "做一个报表");

        assertThat(detail).isNotNull();
        verify(messageService).addMessage(CONV_ID, "user", "做一个报表", null);
        // 原文作 freeform 追问落库（无 progress → payload null）
        verify(messageService).addMessage(CONV_ID, "assistant", "抱歉，我无法帮你澄清。", null);
    }

    @Test
    @DisplayName("含 type 字样但 JSON 解析失败：仍抛 BizException 走重试链路")
    void shouldFailWhenBrokenJsonContainsType() {
        when(conversationService.getById(CONV_ID)).thenReturn(activeConversation());
        stubLlmRound("{\"type\":\"question\",\"message\":");

        assertThatThrownBy(() -> clarifyService.sendMessage(CONV_ID, "做一个报表"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("JSON 解析失败");
        // user 消息已落库保留，assistant 消息未落
        verify(messageService).addMessage(CONV_ID, "user", "做一个报表", null);
        verify(messageService, never()).addMessage(eq(CONV_ID), eq("assistant"), anyString(), any());
    }

    @Test
    @DisplayName("type 非法时报错")
    void shouldFailOnUnknownType() {
        when(conversationService.getById(CONV_ID)).thenReturn(activeConversation());
        stubLlmRound("{\"type\":\"chat\",\"message\":\"你好\"}");

        assertThatThrownBy(() -> clarifyService.sendMessage(CONV_ID, "做一个报表"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("type 非法");
    }

    @Test
    @DisplayName("轮数达到上限时拒发并引导手动创建任务")
    void shouldRejectWhenRoundLimitReached() {
        RequirementConversation conversation = activeConversation();
        conversation.setRoundCount(20);
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);

        assertThatThrownBy(() -> clarifyService.sendMessage(CONV_ID, "再改一下"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("轮数已达上限");
        verify(platformAgentExecutionService, never())
                .executeSync(any(Agent.class), any(AgentTask.class));
        verify(messageService, never()).addMessage(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("非 ACTIVE 会话拒发")
    void shouldRejectWhenConversationNotActive() {
        RequirementConversation conversation = activeConversation();
        conversation.setStatus(RequirementClarifyService.STATUS_FINALIZED);
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);

        assertThatThrownBy(() -> clarifyService.sendMessage(CONV_ID, "再改一下"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话已结束");
    }

    @Test
    @DisplayName("会话不存在时报错")
    void shouldRejectWhenConversationMissing() {
        when(conversationService.getById(CONV_ID)).thenReturn(null);

        assertThatThrownBy(() -> clarifyService.sendMessage(CONV_ID, "你好"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("澄清会话不存在");
    }

    // ══════════════════════════════════════════════════════════════
    //  create
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("create：标题取首条消息截断 50 字，状态 ACTIVE，随即走一轮 LLM")
    void shouldCreateConversationWithTruncatedTitle() {
        String longMessage = "字".repeat(80);
        when(conversationService.save(any(RequirementConversation.class))).thenAnswer(inv -> {
            RequirementConversation c = inv.getArgument(0);
            c.setId(CONV_ID);
            return true;
        });
        stubLlmRound("{\"type\":\"question\",\"message\":\"目标是什么？\"}");

        RequirementClarifyService.ClarifyConversationDetail detail =
                clarifyService.create(longMessage, null);

        RequirementConversation conversation = detail.getConversation();
        assertThat(conversation.getTitle()).hasSize(50);
        assertThat(conversation.getStatus()).isEqualTo(RequirementClarifyService.STATUS_ACTIVE);
        assertThat(conversation.getRoundCount()).isEqualTo(1);
        verify(messageService).addMessage(CONV_ID, "user", longMessage, null);
        verify(messageService).addMessage(CONV_ID, "assistant", "目标是什么？", null);
    }

    @Test
    @DisplayName("create：手动指定 Planner 时严格校验并钉到会话，选人按钉住的 ID 走")
    void shouldCreateWithPinnedPlanner() {
        when(conversationService.save(any(RequirementConversation.class))).thenAnswer(inv -> {
            RequirementConversation c = inv.getArgument(0);
            c.setId(CONV_ID);
            return true;
        });
        when(plannerAgentPicker.pick(9L)).thenReturn(llmPlanner());
        when(messageService.listByConversation(CONV_ID))
                .thenReturn(List.of(message("user", "做一个报表", 1)));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(
                        "{\"type\":\"question\",\"message\":\"目标是什么？\"}", "stop", "llm", 100));

        RequirementClarifyService.ClarifyConversationDetail detail =
                clarifyService.create("做一个报表", 9L);

        assertThat(detail.getConversation().getPlannerAgentId()).isEqualTo(9L);
        verify(plannerAgentPicker).validateSelectable(9L);
        verify(plannerAgentPicker).pick(9L);
    }

    @Test
    @DisplayName("create：指定的 Planner 不可选时拒绝建会，不落库")
    void shouldRejectCreateWhenPinnedPlannerNotSelectable() {
        doThrow(new BizException("外部 Agent 暂不支持对话澄清，请选择平台内 Planner: cli-agent"))
                .when(plannerAgentPicker).validateSelectable(99L);

        assertThatThrownBy(() -> clarifyService.create("做一个报表", 99L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂不支持对话澄清");
        verify(conversationService, never()).save(any(RequirementConversation.class));
    }

    // ════════════════════════════════════════════════════════════
    //  retryRound
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("retry：最后一条是 user 消息时重跑一轮 LLM，不新增 user 消息不加轮数")
    void shouldRetryLlmRoundWithoutNewUserMessage() {
        RequirementConversation conversation = activeConversation();
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

        clarifyService.retryRound(CONV_ID);

        assertThat(conversation.getRoundCount()).isEqualTo(1);
        verify(messageService, never()).addMessage(eq(CONV_ID), eq("user"), anyString(), any());
        verify(messageService).addMessage(CONV_ID, "assistant", "验收标准是什么？", null);
    }

    @Test
    @DisplayName("retry：最后一条已有助手回复时拒绝重试")
    void shouldRejectRetryWhenLastMessageAnswered() {
        when(conversationService.getById(CONV_ID)).thenReturn(activeConversation());
        when(messageService.listByConversation(CONV_ID)).thenReturn(List.of(
                message("user", "做一个报表", 1),
                message("assistant", "目标是什么？", 2)));

        assertThatThrownBy(() -> clarifyService.retryRound(CONV_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无需重试");
        verify(platformAgentExecutionService, never())
                .executeSync(any(Agent.class), any(AgentTask.class));
    }

    // ══════════════════════════════════════════════════════════════
    //  finalize / abandon
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("finalize：无终稿时拒绝")
    void shouldRejectFinalizeWithoutFinalDraft() {
        when(conversationService.getById(CONV_ID)).thenReturn(activeConversation());

        assertThatThrownBy(() -> clarifyService.finalize(CONV_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("尚无终稿");
        verify(taskService, never()).save(any());
    }

    @Test
    @DisplayName("finalize：成功建 PENDING 任务，会话回填 task_id 并 FINALIZED，记 timeline")
    void shouldFinalizeAndCreateTask() {
        RequirementConversation conversation = activeConversation();
        conversation.setFinalTitle("搭建日报模块");
        conversation.setFinalDescription("## 背景\n做日报");
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        when(taskService.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(300L);
            return true;
        });
        when(agentService.listByRole(AgentRole.PLANNER)).thenReturn(List.of(llmPlanner()));

        Task task = clarifyService.finalize(CONV_ID);

        assertThat(task.getId()).isEqualTo(300L);
        assertThat(task.getTitle()).isEqualTo("搭建日报模块");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(conversation.getTaskId()).isEqualTo(300L);
        assertThat(conversation.getStatus()).isEqualTo(RequirementClarifyService.STATUS_FINALIZED);
        verify(conversationService).updateById(conversation);
        verify(agentInboxService).send(eq(9L), anyString(), eq("task.created"),
                anyString(), anyString(), eq("task"), eq(300L), eq("HIGH"));
        verify(taskTimelineService).recordEvent(
                eq(300L), isNull(), eq("task_created_from_clarify"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("finalize：通知 PLANNER 失败不阻断建任务")
    void shouldFinalizeEvenWhenInboxNotifyFails() {
        RequirementConversation conversation = activeConversation();
        conversation.setFinalTitle("标题");
        conversation.setFinalDescription("描述");
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        when(taskService.save(any(Task.class))).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            t.setId(301L);
            return true;
        });
        when(agentService.listByRole(AgentRole.PLANNER)).thenThrow(new RuntimeException("inbox down"));

        Task task = clarifyService.finalize(CONV_ID);

        assertThat(task.getId()).isEqualTo(301L);
        assertThat(conversation.getStatus()).isEqualTo(RequirementClarifyService.STATUS_FINALIZED);
    }

    @Test
    @DisplayName("abandon：ACTIVE → ABANDONED；非 ACTIVE 拒绝")
    void shouldAbandonActiveConversationOnly() {
        RequirementConversation conversation = activeConversation();
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);

        clarifyService.abandon(CONV_ID);
        assertThat(conversation.getStatus()).isEqualTo(RequirementClarifyService.STATUS_ABANDONED);
        verify(conversationService).updateById(conversation);

        assertThatThrownBy(() -> clarifyService.abandon(CONV_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话已结束");
    }
}
