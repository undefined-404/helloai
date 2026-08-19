package com.helloai.core.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.common.config.WebSearchProperties;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.service.WebSearchService;
import com.helloai.core.planner.service.WebPageFetchService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.impl.RequirementClarifyServiceImpl;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import com.helloai.core.planner.search.WebPageContent;
import com.helloai.core.planner.search.WebSearchResult;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Mock
    private WebSearchService webSearchService;

    @Mock
    private WebSearchProperties webSearchProperties;

    @Mock
    private WebPageFetchService pageFetchService;

    private RequirementClarifyService clarifyService;

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）
        clarifyService = new RequirementClarifyServiceImpl(
                conversationService, messageService, taskService, agentService,
                plannerAgentPicker, agentInboxService, platformAgentExecutionService,
                taskTimelineService, new ObjectMapper(),
                webSearchService, webSearchProperties, pageFetchService);
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
    @DisplayName("create：标题取首条消息截断 50 字，状态 ACTIVE，随即走一轮 LLM（显式 CLARIFY 快捷直达）")
    void shouldCreateConversationWithTruncatedTitle() {
        String longMessage = "字".repeat(80);
        when(conversationService.save(any(RequirementConversation.class))).thenAnswer(inv -> {
            RequirementConversation c = inv.getArgument(0);
            c.setId(CONV_ID);
            return true;
        });
        stubLlmRound("{\"type\":\"question\",\"message\":\"目标是什么？\"}");

        RequirementClarifyService.ClarifyConversationDetail detail =
                clarifyService.create(longMessage, null, null, RequirementClarifyService.MODE_CLARIFY);

        RequirementConversation conversation = detail.getConversation();
        assertThat(conversation.getTitle()).hasSize(50);
        assertThat(conversation.getStatus()).isEqualTo(RequirementClarifyService.STATUS_ACTIVE);
        assertThat(conversation.getRoundCount()).isEqualTo(1);
        verify(messageService).addMessage(CONV_ID, "user", longMessage, null);
        verify(messageService).addMessage(CONV_ID, "assistant", "目标是什么？", null);
    }

    @Test
    @DisplayName("create：手动指定 Planner 时严格校验并钉到会话，选人按钉住的 ID 走（显式 CLARIFY）")
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
                clarifyService.create("做一个报表", 9L, null, RequirementClarifyService.MODE_CLARIFY);

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

    // ══════════════════════════════════════════════════════════════
    //  V39 ChatModeAndSwitch：CHAT 自由对话 / CLARIFY 方案澄清双模式
    //  （外层 @BeforeEach 先执行，clarifyService 与全部 @Mock 直接复用）
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("V39 ChatModeAndSwitch：双模式分派与切换")
    class ChatModeAndSwitch {

        private RequirementConversation chatConversation() {
            RequirementConversation conversation = activeConversation();
            conversation.setMode(RequirementClarifyService.MODE_CHAT);
            return conversation;
        }

        /** 走一轮 CHAT LLM 所需的公共 stub（纯文本输出，不经 parseReply）。 */
        private void stubChatLlmRound(String output) {
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "你好", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(output, "stop", "llm", 100));
        }

        @Test
        @DisplayName("CHAT 轮未触发搜索：LLM 纯文本直接落库，payload 为 NULL，round_count+1")
        void chatRoundStoresPlainTextWithoutPayload() {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("你好！请问想了解什么？");

            RequirementClarifyService.ClarifyConversationDetail detail =
                    clarifyService.sendMessage(CONV_ID, "你好");

            assertThat(detail.getConversation().getRoundCount()).isEqualTo(2);
            assertThat(conversation.getFinalTitle()).isNull();
            verify(messageService).addMessage(CONV_ID, "user", "你好", null);
            // payload NULL：纯文本消息，不做 JSON 协议解析
            verify(messageService).addMessage(CONV_ID, "assistant", "你好！请问想了解什么？", null);
            verify(conversationService, times(1)).updateById(conversation);
        }

        @Test
        @DisplayName("CHAT 轮：使用通用助手模板（含 AI 助手角色），不含澄清五维度清单")
        void chatRoundUsesChatPromptTemplate() {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("可以，继续聊。");

            clarifyService.sendMessage(CONV_ID, "你好");

            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .contains("AI 助手")
                    // V40.2 CHAT 模板新增「输出形态」节（追问时可输出 structured JSON）
                    .contains("输出形态")
                    .doesNotContain("五维度自检清单");
        }

        @Test
        @DisplayName("老数据兼容：mode NULL 视为 CLARIFY——澄清模板 + 首轮联网搜索触发（V34 行为回归）")
        void legacyNullModeTreatedAsClarify() {
            RequirementConversation conversation = activeConversation();
            conversation.setRoundCount(0);
            conversation.setMode(null);
            conversation.setWebSearchEnabled(null); // 默认开启
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "做一个报表", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(
                            "{\"type\":\"question\",\"message\":\"验收标准是什么？\"}", "stop", "llm", 100));

            clarifyService.sendMessage(CONV_ID, "做一个报表");

            verify(webSearchService).search(anyString(), eq(5));
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt()).contains("资深需求分析师");
        }

        @Test
        @DisplayName("意图词二次确认：CHAT 消息含「整理成方案」→ 置待确认 + 回复固定确认询问（不调 LLM、不加轮数）")
        void intentPhraseEntersPendingConfirm() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);

            RequirementClarifyService.ClarifyConversationDetail detail =
                    clarifyService.sendMessage(CONV_ID, "帮我分析一下市场，然后整理成方案");

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            assertThat(conversation.getPendingClarifyConfirm()).isTrue();
            // 确认询问不消耗对话轮数
            assertThat(conversation.getRoundCount()).isEqualTo(3);
            assertThat(detail.getConversation().getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            // user 消息 + 固定确认询问落库（V41 起 payload 为结构化确认卡），不调 LLM
            verify(messageService).addMessage(CONV_ID, "user", "帮我分析一下市场，然后整理成方案", null);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq(RequirementClarifyService.CONFIRM_ASK_MESSAGE), anyString());
            verify(platformAgentExecutionService, never())
                    .executeSync(any(Agent.class), any(AgentTask.class));
        }

        @Test
        @DisplayName("口语化意图词（V40.1 扩展）：「帮我整理方案吧」同样进入待确认（不调 LLM、不加轮数）")
        void colloquialIntentPhraseEntersPendingConfirm() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);

            RequirementClarifyService.ClarifyConversationDetail detail =
                    clarifyService.sendMessage(CONV_ID, "帮我整理方案吧");

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            assertThat(conversation.getPendingClarifyConfirm()).isTrue();
            // 确认询问不消耗对话轮数
            assertThat(conversation.getRoundCount()).isEqualTo(3);
            verify(messageService).addMessage(CONV_ID, "user", "帮我整理方案吧", null);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq(RequirementClarifyService.CONFIRM_ASK_MESSAGE), anyString());
            verify(platformAgentExecutionService, never())
                    .executeSync(any(Agent.class), any(AgentTask.class));
        }

        @Test
        @DisplayName("V45 CHAT 轮触发联网搜索：结果注入 CHAT 模板，纯文本回复 payload 携带 webSearch 查验键")
        void chatRoundTriggersWebSearch() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(0);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(false);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                    WebSearchResult.builder().title("OpenMaic 官网")
                            .url("https://open.maic.chat/").snippet("OpenMaic 开放平台官网").build()));
            stubChatLlmRound("你好！");

            clarifyService.sendMessage(CONV_ID, "你好");

            verify(webSearchService).search(eq("你好"), eq(5));
            // 联网资料注入 CHAT 通用助手模板
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt())
                    .contains("AI 助手")
                    .contains("OpenMaic 官网");
            // 纯文本回复也携带 webSearch 查验键（V45，与终稿轮同形态）
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"), eq("你好！"), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"webSearch\"")
                    .contains("OpenMaic 官网")
                    .contains("\"total\":1");
        }

        @Test
        @DisplayName("V45 CHAT 会话开关关闭：不触发联网搜索，payload 保持 NULL")
        void chatRoundWebSearchDisabled() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(0);
            conversation.setWebSearchEnabled(false);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("你好！");

            clarifyService.sendMessage(CONV_ID, "你好");

            verify(webSearchService, never()).search(anyString(), anyInt());
            verify(messageService).addMessage(CONV_ID, "assistant", "你好！", null);
        }

        @Test
        @DisplayName("V45 CHAT 轮 URL 分离：直取页面正文注入 CHAT 模板，payload 携带 fetched 查验键")
        void chatRoundWithUrl_fetchesPageAndInjects() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(0);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.getMaxSnippetChars()).thenReturn(200);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(true);
            when(webSearchProperties.getUrlFetchMaxPages()).thenReturn(2);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());
            when(pageFetchService.fetch("https://open.maic.chat/")).thenReturn(WebPageContent.builder()
                    .url("https://open.maic.chat/").ok(true)
                    .title("OpenMaic 官网").text("这里是官网正文内容").build());
            stubChatLlmRound("好的，这是快速上手手册大纲：");

            clarifyService.sendMessage(CONV_ID, "给我一份快速上手 https://open.maic.chat/ 的操作手册");

            // 搜索词不含裸 URL，只用剥离后的语义文本；直取被提取出的 URL
            ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(webSearchService).search(queryCaptor.capture(), eq(5));
            assertThat(queryCaptor.getValue())
                    .doesNotContain("https://")
                    .contains("快速上手");
            verify(pageFetchService).fetch("https://open.maic.chat/");
            // 直取正文（第一手资料）注入 CHAT 模板
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt()).contains("这里是官网正文内容");
            // 纯文本回复 payload 携带 webSearch + fetched 查验键
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq("好的，这是快速上手手册大纲："), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"webSearch\"")
                    .contains("\"fetched\"")
                    .contains("OpenMaic 官网");
        }

        @Test
        @DisplayName("CHAT 轮数上限 50：49 轮正常续聊")
        void chatRoundFortyNineAllowed() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(49);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("继续。");

            clarifyService.sendMessage(CONV_ID, "继续");

            assertThat(conversation.getRoundCount()).isEqualTo(50);
        }

        @Test
        @DisplayName("CHAT 轮数上限 50：50 轮拒绝（引导转方案或新会话），不调 LLM 不落消息")
        void chatRoundAtLimitRejected() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(50);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);

            assertThatThrownBy(() -> clarifyService.sendMessage(CONV_ID, "再问一下"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("自由对话轮数已达上限");
            verify(platformAgentExecutionService, never())
                    .executeSync(any(Agent.class), any(AgentTask.class));
            verify(messageService, never()).addMessage(anyLong(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("CHAT 达上限后意图词仍放行：进入待确认（保证转方案出口不被 50 轮上限挡住）")
        void intentAtChatLimitStillEntersPendingConfirm() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(50);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);

            clarifyService.sendMessage(CONV_ID, "整理成方案");

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            assertThat(conversation.getPendingClarifyConfirm()).isTrue();
            verify(platformAgentExecutionService, never())
                    .executeSync(any(Agent.class), any(AgentTask.class));
        }

        @Test
        @DisplayName("待确认状态回复确认词「确认」→ 转入 CLARIFY 并清标记，该条消息即澄清首轮（澄清模板 LLM）")
        void confirmPhraseSwitchesToClarifyRound() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "帮我把讨论整理成方案", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(
                            "{\"type\":\"question\",\"message\":\"验收标准是什么？\"}", "stop", "llm", 100));

            clarifyService.sendMessage(CONV_ID, "确认");

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            // 确认条即澄清首轮：轮数 +1，走澄清模板而非 CHAT 模板
            assertThat(conversation.getRoundCount()).isEqualTo(4);
            verify(messageService).addMessage(CONV_ID, "user", "确认", null);
            verify(messageService).addMessage(CONV_ID, "assistant", "验收标准是什么？", null);
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt()).contains("资深需求分析师");
        }

        @Test
        @DisplayName("CHAT 达 50 轮上限后确认词仍放行：确认转 CLARIFY 不被上限挡住")
        void confirmAtChatLimitStillSwitches() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(50);
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "帮我把讨论整理成方案", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(
                            "{\"type\":\"question\",\"message\":\"验收标准是什么？\"}", "stop", "llm", 100));

            clarifyService.sendMessage(CONV_ID, "确认");

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            assertThat(conversation.getRoundCount()).isEqualTo(51);
        }

        @Test
        @DisplayName("待确认状态回复非确认内容 → 清标记继续自由对话（用户放弃转方案）")
        void nonConfirmMessageClearsPendingAndContinuesChat() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("可以，继续聊。");

            clarifyService.sendMessage(CONV_ID, "再讲讲单体架构的细节");

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            // 走正常 CHAT 轮：纯文本落库、轮数 +1
            assertThat(conversation.getRoundCount()).isEqualTo(4);
            verify(messageService).addMessage(CONV_ID, "assistant", "可以，继续聊。", null);
        }

        @Test
        @DisplayName("待确认状态再次输入意图词 → 视为二次确认直接转入 CLARIFY（无需再回「确认」）")
        void intentDuringPendingConfirmEntersClarify() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubLlmRound("{\"type\":\"final\",\"title\":\"搭建日报模块\","
                    + "\"description\":\"## 背景\\n做日报\",\"message\":\"需求已清晰\"}");

            RequirementClarifyService.ClarifyConversationDetail detail =
                    clarifyService.sendMessage(CONV_ID, "整理成方案");

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            assertThat(detail.getConversation().getFinalTitle()).isEqualTo("搭建日报模块");
        }

        @Test
        @DisplayName("create 首条消息含意图词 → 置待确认 + 回复固定确认询问（新会话不直接澄清、不调 LLM）")
        void createIntentPhraseEntersPendingConfirm() {
            when(conversationService.save(any(RequirementConversation.class))).thenAnswer(inv -> {
                RequirementConversation c = inv.getArgument(0);
                c.setId(CONV_ID);
                return true;
            });

            RequirementClarifyService.ClarifyConversationDetail detail =
                    clarifyService.create("帮我整理成方案：做一个周报工具", null);

            RequirementConversation conversation = detail.getConversation();
            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            assertThat(conversation.getPendingClarifyConfirm()).isTrue();
            // 确认询问不消耗对话轮数（0 轮保持）
            assertThat(conversation.getRoundCount()).isEqualTo(0);
            verify(messageService).addMessage(CONV_ID, "user", "帮我整理成方案：做一个周报工具", null);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq(RequirementClarifyService.CONFIRM_ASK_MESSAGE), anyString());
            verify(platformAgentExecutionService, never())
                    .executeSync(any(Agent.class), any(AgentTask.class));
        }

        @Test
        @DisplayName("create 缺省初始模式为 CHAT（V39 产品决策：新会话默认自由对话）")
        void createDefaultsToChatMode() {
            when(conversationService.save(any(RequirementConversation.class))).thenAnswer(inv -> {
                RequirementConversation c = inv.getArgument(0);
                c.setId(CONV_ID);
                return true;
            });
            stubChatLlmRound("你好！");

            RequirementClarifyService.ClarifyConversationDetail detail = clarifyService.create("你好", null);

            assertThat(detail.getConversation().getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            verify(messageService).addMessage(CONV_ID, "assistant", "你好！", null);
        }

        @Test
        @DisplayName("create：initialMode 非法值拒绝建会")
        void createRejectsInvalidInitialMode() {
            assertThatThrownBy(() -> clarifyService.create("你好", null, null, "BOGUS"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("非法的初始对话模式");
            verify(conversationService, never()).save(any(RequirementConversation.class));
        }

        @Test
        @DisplayName("to-clarify：置位 CLARIFY + 一轮澄清 LLM 基于全量历史产追问（澄清模板），并清除待确认标记")
        void switchToClarifyRunsClarifyRound() {
            RequirementConversation conversation = chatConversation();
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "做一个报表", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(
                            "{\"type\":\"question\",\"message\":\"验收标准是什么？\"}", "stop", "llm", 100));

            clarifyService.switchToClarify(CONV_ID);

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            verify(messageService).addMessage(CONV_ID, "assistant", "验收标准是什么？", null);
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt()).contains("资深需求分析师");
        }

        @Test
        @DisplayName("to-clarify：LLM 失败时 mode 已持久化 CLARIFY + 抛 BizException（前端可重试）")
        void switchToClarifyPersistsModeEvenOnLlmFailure() {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "做一个报表", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.failure("llm down", "stop", "llm"));

            assertThatThrownBy(() -> clarifyService.switchToClarify(CONV_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("LLM 调用失败");
            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
        }

        @Test
        @DisplayName("to-clarify 带附加文本（V40.2 /planner 命令路径）：先落库 user 消息进上下文，再切 CLARIFY 跑澄清轮")
        void switchToClarifyWithExtraMessage() {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "做一个报表", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(
                            "{\"type\":\"question\",\"mode\":\"structured\",\"progress\":40,"
                                    + "\"message\":\"需要确认几个关键点\","
                                    + "\"questions\":[{\"text\":\"业务类型？\",\"options\":"
                                    + "[{\"label\":\"企业内部工具\",\"value\":\"opt_a\",\"recommended\":true},"
                                    + "{\"label\":\"SaaS 产品\",\"value\":\"opt_b\",\"recommended\":false}]}]}",
                            "stop", "llm", 100));

            clarifyService.switchToClarify(CONV_ID, "补充：团队 10 人，单体优先");

            // 附加文本先进上下文（user 消息，无 payload），随后模式切换 + 澄清轮
            verify(messageService).addMessage(CONV_ID, "user", "补充：团队 10 人，单体优先", null);
            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"), contentCaptor.capture(),
                    payloadCaptor.capture());
            // content 为可读拼接文本（进 LLM 上下文），payload 为 structured 快照（前端渲染推荐卡片）
            assertThat(contentCaptor.getValue()).contains("业务类型？（选项：企业内部工具 / SaaS 产品）");
            assertThat(payloadCaptor.getValue()).contains("\"mode\":\"structured\"")
                    .contains("\"recommended\":true");
        }

        @Test
        @DisplayName("to-clarify 附加文本为空/空白：不加 user 消息，与既有 switchToClarify 行为一致")
        void switchToClarifyWithBlankExtraEqualsLegacy() {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "做一个报表", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(
                            "{\"type\":\"question\",\"message\":\"验收标准是什么？\"}", "stop", "llm", 100));

            clarifyService.switchToClarify(CONV_ID, "   ");

            verify(messageService, never()).addMessage(CONV_ID, "user", "   ", null);
            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
            verify(messageService).addMessage(CONV_ID, "assistant", "验收标准是什么？", null);
        }

        @Test
        @DisplayName("CHAT 容错双模（V40.2）：LLM 输出 structured 追问 → payload 落库出推荐卡片，模式仍 CHAT（未配置搜索参数时不触发搜索）")
        void chatRoundStructuredQuestionStoresPayload() {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("""
                    ```json
                    {"type":"question","mode":"structured","message":"帮我确认两点：",
                    "questions":[{"id":"q1","text":"业务类型？","multiple":false,"allowCustom":true,
                    "options":[{"label":"企业内部工具","value":"opt_a","recommended":true},
                    {"label":"SaaS 产品","value":"opt_b","recommended":false}]}]}
                    ```
                    """);

            RequirementClarifyService.ClarifyConversationDetail detail =
                    clarifyService.sendMessage(CONV_ID, "帮我做技术选型");

            assertThat(detail.getConversation().getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"), contentCaptor.capture(),
                    payloadCaptor.capture());
            assertThat(contentCaptor.getValue()).contains("业务类型？（选项：企业内部工具 / SaaS 产品）");
            assertThat(payloadCaptor.getValue()).contains("\"mode\":\"structured\"")
                    .contains("\"recommended\":true");
            // 未配置搜索参数（queryKeywordLimit mock 默认 0 → 查询词为空）时不触发联网搜索
            verify(webSearchService, never()).search(anyString(), anyInt());
        }

        @Test
        @DisplayName("CHAT 容错双模（V40.2）：freeform JSON / 非结构化输出仍按纯文本落库（payload NULL，零破坏）")
        void chatRoundFreeformJsonStillPlain() {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("{\"type\":\"question\",\"mode\":\"freeform\",\"message\":\"请贴出接口文档\"}");

            clarifyService.sendMessage(CONV_ID, "帮我做技术选型");

            // freeform 在 CHAT 视为普通文本：原文落库、payload NULL
            verify(messageService).addMessage(CONV_ID, "assistant",
                    "{\"type\":\"question\",\"mode\":\"freeform\",\"message\":\"请贴出接口文档\"}", null);
            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
        }

        @Test
        @DisplayName("to-chat：仅置位 CHAT，不调用 LLM")
        void switchToChatFlipsModeOnly() {
            RequirementConversation conversation = activeConversation();
            conversation.setMode(RequirementClarifyService.MODE_CLARIFY);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(messageService.listByConversation(CONV_ID)).thenReturn(List.of());

            clarifyService.switchToChat(CONV_ID);

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            verify(platformAgentExecutionService, never())
                    .executeSync(any(Agent.class), any(AgentTask.class));
        }

        @Test
        @DisplayName("FINALIZED 会话 to-clarify/to-chat 均拒绝")
        void finalizedCannotSwitchMode() {
            RequirementConversation conversation = activeConversation();
            conversation.setStatus(RequirementClarifyService.STATUS_FINALIZED);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);

            assertThatThrownBy(() -> clarifyService.switchToClarify(CONV_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("会话已结束");
            assertThatThrownBy(() -> clarifyService.switchToChat(CONV_ID))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("会话已结束");
            verify(conversationService, never()).updateById(any());
        }

        // ════════════════════════════════════════════════════════
        //  V41 意图词扩充 + 确认卡结构化
        // ════════════════════════════════════════════════════════

        @Test
        @DisplayName("V41 新意图词：「新建个计划吧」进入待确认并发结构化确认卡（仅确认/取消、无推荐、无自定义）")
        void doRound_newIntentPhrase_setsPendingConfirmAndSendsCard() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);

            clarifyService.sendMessage(CONV_ID, "新建个计划吧");

            assertThat(conversation.getPendingClarifyConfirm()).isTrue();
            // 确认询问不消耗对话轮数，不调 LLM
            assertThat(conversation.getRoundCount()).isEqualTo(3);
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq(RequirementClarifyService.CONFIRM_ASK_MESSAGE), payloadCaptor.capture());
            verify(platformAgentExecutionService, never())
                    .executeSync(any(Agent.class), any(AgentTask.class));
            assertThat(payloadCaptor.getValue())
                    .contains("\"mode\":\"structured\"")
                    .contains("\"confirm-switch\"")
                    .contains("确认").contains("取消")
                    .doesNotContain("recommended");
        }

        @Test
        @DisplayName("V41 确认卡 payload：仅 1 题 2 选项，allowCustom=false 且无 recommended 标记")
        void doRound_intentHit_confirmPayloadHasTwoOptionsWithoutRecommended() throws Exception {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);

            clarifyService.sendMessage(CONV_ID, "给一个方案");

            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq(RequirementClarifyService.CONFIRM_ASK_MESSAGE), payloadCaptor.capture());
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(payloadCaptor.getValue());
            assertThat(root.get("mode").asText()).isEqualTo("structured");
            com.fasterxml.jackson.databind.JsonNode questions = root.get("questions");
            assertThat(questions).hasSize(1);
            com.fasterxml.jackson.databind.JsonNode question = questions.get(0);
            assertThat(question.get("id").asText()).isEqualTo("confirm-switch");
            assertThat(question.get("multiple").asBoolean()).isFalse();
            assertThat(question.get("allowCustom").asBoolean()).isFalse();
            com.fasterxml.jackson.databind.JsonNode options = question.get("options");
            assertThat(options).hasSize(2);
            assertThat(options.get(0).get("label").asText()).isEqualTo("确认");
            assertThat(options.get(1).get("label").asText()).isEqualTo("取消");
            for (com.fasterxml.jackson.databind.JsonNode option : options) {
                assertThat(option.has("recommended")).isFalse();
            }
        }

        @Test
        @DisplayName("V41 新意图词覆盖：「给一个方案/帮我总结一下/新建个任务吧/帮我生成计划/来一个计划」均进入待确认")
        void doRound_newIntentPhrases_allEnterPendingConfirm() {
            for (String phrase : List.of("给一个方案", "帮我总结一下", "新建个任务吧", "帮我生成计划", "来一个计划")) {
                RequirementConversation conversation = chatConversation();
                when(conversationService.getById(CONV_ID)).thenReturn(conversation);

                clarifyService.sendMessage(CONV_ID, phrase);

                assertThat(conversation.getPendingClarifyConfirm()).as(phrase).isTrue();
            }
            verify(platformAgentExecutionService, never())
                    .executeSync(any(Agent.class), any(AgentTask.class));
        }

        @Test
        @DisplayName("V41 防误触：含裸词「任务」的普通提问不触发确认，正常走 CHAT 轮")
        void doRound_bareTaskWord_doesNotTriggerConfirm() {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("2 号任务正在执行中。");

            clarifyService.sendMessage(CONV_ID, "2 号任务进展如何");

            assertThat(conversation.getPendingClarifyConfirm()).isNotEqualTo(Boolean.TRUE);
            assertThat(conversation.getRoundCount()).isEqualTo(2);
            verify(messageService).addMessage(CONV_ID, "assistant", "2 号任务正在执行中。", null);
        }

        @Test
        @DisplayName("V41 确认卡点「确认」：经 selections 快照判定转入 CLARIFY（提交文本非确认词开头也能切换）")
        void doRound_confirmCardAccept_switchesToClarify() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "新建个计划吧", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(
                            "{\"type\":\"question\",\"message\":\"验收标准是什么？\"}", "stop", "llm", 100));

            // 卡片提交文本形如「问题：确认」，不命中 CONFIRM_PHRASE_PATTERN 开头锚定，靠快照判定
            RequirementClarifyService.ClarifySelection selection =
                    new RequirementClarifyService.ClarifySelection();
            selection.setQuestionId("confirm-switch");
            selection.setQuestionText("检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？");
            selection.setValues(List.of("确认"));
            selection.setLabels(List.of("确认"));

            clarifyService.sendMessage(CONV_ID,
                    "检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？：确认", List.of(selection));

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            assertThat(conversation.getRoundCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("V41 确认卡点「取消」：清标记继续 CHAT（不调澄清模板）")
        void doRound_confirmCardCancel_continuesChat() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("好的，继续聊。");

            RequirementClarifyService.ClarifySelection selection =
                    new RequirementClarifyService.ClarifySelection();
            selection.setQuestionId("confirm-switch");
            selection.setQuestionText("检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？");
            selection.setValues(List.of("取消"));
            selection.setLabels(List.of("取消"));

            clarifyService.sendMessage(CONV_ID,
                    "检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？：取消", List.of(selection));

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            assertThat(conversation.getRoundCount()).isEqualTo(4);
            verify(messageService).addMessage(CONV_ID, "assistant", "好的，继续聊。", null);
        }

        // ════════════════════════════════════════════════════════
        //  V41 联网搜索：多轮触发 + payload 查验
        // ════════════════════════════════════════════════════════

        @Test
        @DisplayName("V41 多轮搜索：CLARIFY 第 2 轮也触发搜索，assistant payload 含 webSearch 查验键")
        void doRound_clarifySecondRound_triggersWebSearchAndPayload() {
            // activeConversation：roundCount=1（第 2 轮），mode NULL 按 CLARIFY，webSearchEnabled NULL 默认开启
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                    WebSearchResult.builder().title("报表方案参考")
                            .url("https://a.example/1").snippet("摘要一").siteName("站点A").build(),
                    WebSearchResult.builder().title("报表工具选型")
                            .url("https://a.example/2").snippet("摘要二").siteName("站点B").build()));
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID, "做一个报表");

            // 第 2 轮（roundCount=1）同样触发搜索，查询词取当前轮消息
            verify(webSearchService).search(eq("做一个报表"), eq(5));
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    anyString(), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"webSearch\"")
                    .contains("\"provider\":\"bocha\"")
                    .contains("\"query\":\"做一个报表\"")
                    .contains("\"total\":2")
                    .contains("报表方案参考")
                    .contains("https://a.example/1");
        }

        @Test
        @DisplayName("V41 修复：确认卡切入方案时搜索词不用卡片提交文本，回退历史主题消息")
        void doRound_confirmCardAccept_searchQueryFallsBackToHistoryTopic() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                    WebSearchResult.builder().title("OpenMaaS 介绍")
                            .url("https://a.example/1").snippet("摘要").build()));
            // 历史：主题讨论 → 纯意图短句（应被跳过）→ 确认询问（assistant，跳过）
            when(messageService.listByConversation(CONV_ID)).thenReturn(List.of(
                    message("user", "你知道openMaic么？你知道这个怎么使用么？", 1),
                    message("user", "帮我整理成方案", 2),
                    message("assistant", "检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？", 3)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(
                            "{\"type\":\"question\",\"message\":\"验收标准是什么？\"}", "stop", "llm", 100));

            RequirementClarifyService.ClarifySelection selection =
                    new RequirementClarifyService.ClarifySelection();
            selection.setQuestionId("confirm-switch");
            selection.setQuestionText("检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？");
            selection.setValues(List.of("确认"));
            selection.setLabels(List.of("确认"));

            clarifyService.sendMessage(CONV_ID,
                    "检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？：确认", List.of(selection));

            // 搜索词回退为触发意图前的讨论主题，而非卡片提交文本/纯意图短句
            verify(webSearchService).search(eq("你知道openMaic么？你知道这个怎么使用么？"), eq(5));
        }

        @Test
        @DisplayName("V41 修复：确认后历史无可回退主题消息时不发起搜索（不落查验条）")
        void doRound_confirmCardAccept_noMeaningfulHistory_skipsSearch() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(1);
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            // 历史只有纯意图短句，无可检索主题
            when(messageService.listByConversation(CONV_ID)).thenReturn(List.of(
                    message("user", "帮我生成计划", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success(
                            "{\"type\":\"question\",\"message\":\"验收标准是什么？\"}", "stop", "llm", 100));

            RequirementClarifyService.ClarifySelection selection =
                    new RequirementClarifyService.ClarifySelection();
            selection.setQuestionId("confirm-switch");
            selection.setQuestionText("检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？");
            selection.setValues(List.of("确认"));
            selection.setLabels(List.of("确认"));

            clarifyService.sendMessage(CONV_ID,
                    "检测到你想把讨论整理成落地方案，是否切换到方案澄清模式？：确认", List.of(selection));

            verify(webSearchService, never()).search(anyString(), anyInt());
        }

        @Test
        @DisplayName("V41 搜索异常查验：search 抛异常降级 failed=true 落 payload，澄清主流程不阻断")
        void doRound_webSearchThrows_payloadMarksFailedWithoutBlocking() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt()))
                    .thenThrow(new RuntimeException("bocha timeout"));
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID, "做一个报表");

            // 主流程不阻断：澄清回复正常落库，payload 携带 failed 查验信息
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq("验收标准是什么？"), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"webSearch\"")
                    .contains("\"failed\":true")
                    .contains("bocha timeout")
                    .contains("\"total\":0");
        }

        @Test
        @DisplayName("V43 URL 分离：搜索词用剥离 URL 的语义文本，直取页面置顶作来源")
        void doRound_messageWithUrl_separatesUrlAndFetchesPage() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.getMaxSnippetChars()).thenReturn(200);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(true);
            when(webSearchProperties.getUrlFetchMaxPages()).thenReturn(2);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                    WebSearchResult.builder().title("搜索结果")
                            .url("https://s.example/1").snippet("搜索摘要").build()));
            when(pageFetchService.fetch("https://open.maic.chat/")).thenReturn(WebPageContent.builder()
                    .url("https://open.maic.chat/").ok(true)
                    .title("OpenMaic 官网").text("这里是官网正文内容").build());
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID,
                    "给我一份快速上手 https://open.maic.chat/ 的操作手册");

            // 搜索词不含裸 URL，只用剥离后的语义文本
            ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(webSearchService).search(queryCaptor.capture(), eq(5));
            assertThat(queryCaptor.getValue())
                    .doesNotContain("https://")
                    .contains("快速上手");
            // 直取被提取出的 URL
            verify(pageFetchService).fetch("https://open.maic.chat/");
            // payload：直取来源置顶可见 + fetched 直取记录落键
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq("验收标准是什么？"), payloadCaptor.capture());
            String payload = payloadCaptor.getValue();
            assertThat(payload)
                    .contains("\"webSearch\"")
                    .contains("OpenMaic 官网")
                    .contains("\"fetched\"")
                    .contains("\"ok\":true");
            assertThat(payload.indexOf("OpenMaic 官网"))
                    .isLessThan(payload.indexOf("搜索结果"));
        }

        @Test
        @DisplayName("V43 纯 URL 消息：搜索词回退域名，直取照常发起")
        void doRound_bareUrlMessage_queryFallsBackToDomain() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.getMaxSnippetChars()).thenReturn(200);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(true);
            when(webSearchProperties.getUrlFetchMaxPages()).thenReturn(2);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());
            when(pageFetchService.fetch("https://open.maic.chat/")).thenReturn(WebPageContent.builder()
                    .url("https://open.maic.chat/").ok(true)
                    .title("OpenMaic").text("正文").build());
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID, "https://open.maic.chat/");

            // 剥离 URL 后无文本 → 回退域名作搜索词
            verify(webSearchService).search(eq("open.maic.chat"), eq(5));
            verify(pageFetchService).fetch("https://open.maic.chat/");
        }

        @Test
        @DisplayName("V43 URL 直取失败：不进来源列表，失败记录落 payload 可查验")
        void doRound_pageFetchFailed_recordedButNotInResults() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.getMaxSnippetChars()).thenReturn(200);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(true);
            when(webSearchProperties.getUrlFetchMaxPages()).thenReturn(2);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                    WebSearchResult.builder().title("搜索结果")
                            .url("https://s.example/1").snippet("搜索摘要").build()));
            when(pageFetchService.fetch(anyString())).thenReturn(WebPageContent.builder()
                    .url("https://open.maic.chat/").ok(false)
                    .reason("HTTP 403").title("").text("").build());
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID, "介绍下 https://open.maic.chat/ 这个平台");

            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq("验收标准是什么？"), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"fetched\"")
                    .contains("\"ok\":false")
                    .contains("HTTP 403")
                    .contains("\"total\":1"); // 仅搜索结果一条，失败直取不进来源
        }

        @Test
        @DisplayName("V44 直取全部失败：域名前置增强搜索词（检索站点公开资料）")
        void doRound_pageFetchFailed_queryGetsDomainPrefix() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.getMaxSnippetChars()).thenReturn(200);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(true);
            when(webSearchProperties.getUrlFetchMaxPages()).thenReturn(2);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());
            when(pageFetchService.fetch(anyString())).thenReturn(WebPageContent.builder()
                    .url("https://open.maic.chat/").ok(false)
                    .reason("页面正文为空且无元数据").title("").text("").build());
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID, "介绍下 https://open.maic.chat/ 这个平台");

            // 直取失败 → 搜索词前置域名，让搜索引擎检索该站点公开资料
            ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(webSearchService).search(queryCaptor.capture(), eq(5));
            assertThat(queryCaptor.getValue())
                    .startsWith("open.maic.chat ")
                    .contains("介绍下");
        }

        @Test
        @DisplayName("V44 SPA 空壳元数据兜底：metaOnly 直取进来源且 payload 落标记")
        void doRound_metaOnlyFetch_mergedAndMarkedInPayload() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.getMaxSnippetChars()).thenReturn(200);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(true);
            when(webSearchProperties.getUrlFetchMaxPages()).thenReturn(2);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());
            when(pageFetchService.fetch("https://open.maic.chat/")).thenReturn(WebPageContent.builder()
                    .url("https://open.maic.chat/").ok(true).metaOnly(true)
                    .title("OpenMaic 开放平台")
                    .text("站点名称：OpenMaic 开放平台；站点描述：多智能体协作平台").build());
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID, "介绍下 https://open.maic.chat/ 这个平台");

            // 元数据兜底视为成功直取 → 搜索词不加域名前缀（第一手资料已在手）
            ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(webSearchService).search(queryCaptor.capture(), eq(5));
            assertThat(queryCaptor.getValue()).doesNotContain("open.maic.chat");
            // payload：metaOnly 直取来源进结果列表 + fetched 记录带 metaOnly 标记
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq("验收标准是什么？"), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("OpenMaic 开放平台")
                    .contains("\"metaOnly\":true")
                    .contains("\"total\":1");
        }

        @Test
        @DisplayName("V43 URL 直取开关关闭：不发起抓取，回退纯搜索引擎行为")
        void doRound_urlFetchDisabled_skipsPageFetch() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(false);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID, "介绍下 https://open.maic.chat/ 这个平台");

            verify(pageFetchService, never()).fetch(anyString());
            verify(webSearchService).search(anyString(), eq(5));
        }
    }
}
