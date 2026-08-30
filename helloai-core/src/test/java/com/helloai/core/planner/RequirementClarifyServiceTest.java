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
import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.clarify.ClarifyReplyParser;
import com.helloai.core.planner.clarify.ClarifyWebSearchOrchestrator;
import com.helloai.core.planner.clarify.ConfirmCardProtocol;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.entity.RequirementMessage;
import com.helloai.core.planner.service.WebSearchService;
import com.helloai.core.planner.service.WebPageFetchService;
import com.helloai.core.planner.service.SearchQueryPlannerService;
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
import static org.mockito.ArgumentMatchers.argThat;
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

    @Mock
    private SearchQueryPlannerService searchQueryPlannerService;

    private RequirementClarifyService clarifyService;

    @BeforeEach
    void setUp() {
        // ObjectMapper 用真实实例（JSON 解析是被测逻辑本身，不 mock）；
        // searchQueryPlannerService 默认 mock 返回空列表 → 走规则截断兜底路径（存量用例行为不变）；
        // 拆分组件 ClarifyReplyParser/ConfirmCardProtocol/ClarifyWebSearchOrchestrator/ChatRoundDecisionParser
        // 用真实实例直连（纯函数/编排可测，无需 mock）；搜索服务 mock 不变
        clarifyService = new RequirementClarifyServiceImpl(
                conversationService, messageService, taskService, agentService,
                plannerAgentPicker, agentInboxService, platformAgentExecutionService,
                taskTimelineService, new ClarifyReplyParser(new ObjectMapper()),
                new ConfirmCardProtocol(new ObjectMapper()),
                new ClarifyWebSearchOrchestrator(webSearchService, webSearchProperties,
                        pageFetchService, searchQueryPlannerService),
                new ChatRoundDecisionParser(new ObjectMapper()));
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

    /** 打通一轮 CHAT LLM 调用所需的公共 stub（纯文本输出，不经 parseReply）。 
     *  先注册主回复 any stub 再注册决策 scene stub（Mockito 后注册优先：决策调用命中 scene stub，
     *  主回复调用回退 any stub）——CHAT 轮现在是「决策 + 主回复」两次 executeSync。 */
    private void stubChatLlmRound(String output) {
        when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
        when(messageService.listByConversation(CONV_ID))
                .thenReturn(List.of(message("user", "你好", 1)));
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success(output, "stop", "llm", 100));
        stubDecisionRound(DECISION_CHAT_NO_SEARCH);
    }

    /** 联合决策 AgentTask 的 scene 标识（与服务实现常量一致，供 stub/verify 区分决策轮与主回复轮）。 */
    private static final String DECISION_SCENE = "requirement_chat_decision";

    /** 默认决策输出：chat 意图、不搜索（存量 CHAT 用例的决策轮 stub 默认值）。 */
    private static final String DECISION_CHAT_NO_SEARCH = """
            {"intent":"chat","intent_reason":"direct_answer","clarification_question":null,
             "web_search":{"need_search":false,"search_query":null,"reason":"无需搜索"}}
            """;

    /** 构造带搜索的 chat 决策 JSON（LLM 优化词参数化，供搜索断言用例用）。 */
    private static String decisionChatWithSearch(String llmQuery) {
        return "{\"intent\":\"chat\",\"intent_reason\":\"direct_answer\",\"clarification_question\":null,"
                + "\"web_search\":{\"need_search\":true,\"search_query\":\"" + llmQuery
                + "\",\"reason\":\"用户询问时效性信息\"}}";
    }

    /** 决策轮 executeSync stub：按 AgentTask.context.scene=requirement_chat_decision 精确匹配。 */
    private void stubDecisionRound(String decisionJson) {
        when(platformAgentExecutionService.executeSync(any(Agent.class),
                argThat(task -> task != null
                        && DECISION_SCENE.equals(task.getContext() == null
                        ? null : task.getContext().get("scene")))))
                .thenReturn(AgentResult.success(decisionJson, "stop", "llm", 100));
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
    @DisplayName("structured 追问：校验通过后 payload 落库，content 合成可读文本")
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
    @DisplayName("structured 校验失败（选项为空）：降级 freeform，questions 丢弃")
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
    @DisplayName("sendMessage 附选项快照：user 消息 payload 存 selections")
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
    @DisplayName("非 JSON 且不含 type 字样：降级为 freeform 追问落库，不报错")
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
    @DisplayName("create：标题取首条消息截断 50 字，状态 ACTIVE，随即走一轮 CHAT LLM")
    void shouldCreateConversationWithTruncatedTitle() {
        String longMessage = "字".repeat(80);
        when(conversationService.save(any(RequirementConversation.class))).thenAnswer(inv -> {
            RequirementConversation c = inv.getArgument(0);
            c.setId(CONV_ID);
            return true;
        });
        stubChatLlmRound("好的，我们开始。");

        RequirementClarifyService.ClarifyConversationDetail detail =
                clarifyService.create(longMessage, null, null);

        RequirementConversation conversation = detail.getConversation();
        assertThat(conversation.getTitle()).hasSize(50);
        assertThat(conversation.getStatus()).isEqualTo(RequirementClarifyService.STATUS_ACTIVE);
        assertThat(conversation.getRoundCount()).isEqualTo(1);
        verify(messageService).addMessage(CONV_ID, "user", longMessage, null);
        verify(messageService).addMessage(CONV_ID, "assistant", "好的，我们开始。", null);
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
        // 决策轮与主回复轮各 pick 一次（钉住的 Planner 跟随到两轮调用）
        verify(plannerAgentPicker, times(2)).pick(9L);
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
    @DisplayName("retry：最后一条是 user 消息时重跑一轮 LLM（含联网搜索），不新增 user 消息不加轮数")
    void shouldRetryLlmRoundWithoutNewUserMessage() {
        RequirementConversation conversation = activeConversation();
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
        when(webSearchProperties.getMaxResults()).thenReturn(5);
        when(webSearchService.provider()).thenReturn("bocha");
        when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                WebSearchResult.builder().title("OpenMaic 官网")
                        .url("https://open.maic.chat/").snippet("OpenMaic 开放平台官网").build()));
        stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

        clarifyService.retryRound(CONV_ID);

        assertThat(conversation.getRoundCount()).isEqualTo(1);
        verify(messageService, never()).addMessage(eq(CONV_ID), eq("user"), anyString(), any());
        // 重试轮同样触发联网搜索（搜索词 = 最后一条 user 消息），结果注入 Prompt 与 payload
        verify(webSearchService).search(eq("做一个报表"), eq(5));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(CONV_ID), eq("assistant"), eq("验收标准是什么？"),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .contains("\"webSearch\"")
                .contains("OpenMaic 官网")
                .contains("\"total\":1");
    }

    @Test
    @DisplayName("retry：会话联网搜索开关关闭时重跑不检索，payload 保持 NULL")
    void shouldRetryWithoutWebSearchWhenDisabled() {
        RequirementConversation conversation = activeConversation();
        conversation.setWebSearchEnabled(false);
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

        clarifyService.retryRound(CONV_ID);

        verify(webSearchService, never()).search(anyString(), anyInt());
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

    @Test
    @DisplayName("retry（CHAT 模式）：重跑 决策 + 搜索（LLM 优化词）+ 主回复，不新增 user 消息不加轮数")
    void shouldRetryChatRoundWithDecisionAndSearch() {
        RequirementConversation conversation = activeConversation();
        conversation.setMode(RequirementClarifyService.MODE_CHAT);
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);
        when(webSearchProperties.getMaxResults()).thenReturn(5);
        when(webSearchService.provider()).thenReturn("bocha");
        when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                WebSearchResult.builder().title("行情速递")
                        .url("https://a.example/1").snippet("摘要").build()));
        when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
        when(messageService.listByConversation(CONV_ID))
                .thenReturn(List.of(message("user", "最新行情怎样", 1)));
        // 先注册主回复 any stub，再注册决策轮 stub（retry 同样走 决策 → 搜索 → 主回复）
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("行情如下：", "stop", "llm", 100));
        stubDecisionRound(decisionChatWithSearch("最新行情"));

        clarifyService.retryRound(CONV_ID);

        // 重试不加用户消息、不加轮数（失败那轮已计入）
        verify(messageService, never()).addMessage(eq(CONV_ID), eq("user"), anyString(), any());
        assertThat(conversation.getRoundCount()).isEqualTo(1);
        // LLM 优化词优先搜索，主回复落库（payload 携带 webSearch 查验键）
        verify(webSearchService).search(eq("最新行情"), eq(5));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(messageService).addMessage(eq(CONV_ID), eq("assistant"), eq("行情如下："),
                payloadCaptor.capture());
        assertThat(payloadCaptor.getValue()).contains("\"webSearch\"").contains("行情速递");
        // 决策轮与主回复轮各恰一次（§6.166 同语义：retry 与 sendMessage 走同一 runRoundCore）
        verify(platformAgentExecutionService, times(1)).executeSync(any(Agent.class),
                argThat(task -> task != null
                        && DECISION_SCENE.equals(task.getContext() == null
                        ? null : task.getContext().get("scene"))));
        verify(platformAgentExecutionService, times(1)).executeSync(any(Agent.class),
                argThat(task -> task != null
                        && "requirement_chat".equals(task.getContext() == null
                        ? null : task.getContext().get("scene"))));
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

    @Test
    @DisplayName("delete：仅 ABANDONED 放行；消息与会话逻辑删除各一次")
    void shouldDeleteAbandonedConversation() {
        RequirementConversation conversation = activeConversation();
        conversation.setStatus(RequirementClarifyService.STATUS_ABANDONED);
        when(conversationService.getById(CONV_ID)).thenReturn(conversation);

        clarifyService.delete(CONV_ID);

        verify(messageService).removeByConversation(CONV_ID);
        verify(conversationService).removeById(CONV_ID);
    }

    @Test
    @DisplayName("delete：非 ABANDONED（ACTIVE）拒绝且不删任何数据")
    void shouldRejectDeleteNonAbandonedConversation() {
        when(conversationService.getById(CONV_ID)).thenReturn(activeConversation());

        assertThatThrownBy(() -> clarifyService.delete(CONV_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("仅已放弃的会话可删除");
        verify(messageService, never()).removeByConversation(anyLong());
        verify(conversationService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("delete：会话不存在拒绝")
    void shouldRejectDeleteMissingConversation() {
        when(conversationService.getById(CONV_ID)).thenReturn(null);

        assertThatThrownBy(() -> clarifyService.delete(CONV_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("澄清会话不存在");
        verify(messageService, never()).removeByConversation(anyLong());
        verify(conversationService, never()).removeById(anyLong());
    }

    // ══════════════════════════════════════════════════════════════
    //  ChatModeAndSwitch：CHAT 自由对话 / CLARIFY 方案澄清双模式
    //  （外层 @BeforeEach 先执行，clarifyService 与全部 @Mock 直接复用）
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ChatModeAndSwitch：双模式分派与切换")
    class ChatModeAndSwitch {

        private RequirementConversation chatConversation() {
            RequirementConversation conversation = activeConversation();
            conversation.setMode(RequirementClarifyService.MODE_CHAT);
            return conversation;
        }

        /** 构造确认卡确认选择（selections 快照元素）。 */
        private RequirementClarifyService.ClarifySelection createConfirmSelection(String value) {
            RequirementClarifyService.ClarifySelection selection =
                    new RequirementClarifyService.ClarifySelection();
            selection.setQuestionId(ConfirmCardProtocol.CONFIRM_QUESTION_ID);
            selection.setQuestionText(ConfirmCardProtocol.CONFIRM_QUESTION_TEXT);
            selection.setValues(List.of(value));
            selection.setLabels(List.of(value));
            return selection;
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
            // AUTO 决策 need_search=false：本轮不触发搜索
            verify(webSearchService, never()).search(anyString(), anyInt());
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
            verify(platformAgentExecutionService, times(2)).executeSync(any(Agent.class), taskCaptor.capture());
            // 第 2 次调用是主回复轮（第 1 次为联合决策轮），取主回复 prompt 断言模板
            assertThat(taskCaptor.getAllValues().get(1).getUserPrompt())
                    .contains("AI 助手")
                    // CHAT 模板新增「输出形态」节（追问时可输出 structured JSON）
                    .contains("输出形态")
                    .doesNotContain("五维度自检清单");
        }

        @Test
        @DisplayName("老数据兼容：mode NULL 视为 CLARIFY——澄清模板 + 首轮联网搜索触发（行为回归）")
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
        @DisplayName("意图词不再触发正则匹配：LLM auto 意图路由替代，意图词走正常 CHAT LLM 调用")
        void intentPhraseGoesThroughNormalChatLlm() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("好的，我帮你梳理一下市场分析要点：");

            clarifyService.sendMessage(CONV_ID, "帮我分析一下市场，然后整理成方案");

            // 意图词不再触发 pendingClarifyConfirm（LLM auto 意图路由替代正则）
            assertThat(conversation.getPendingClarifyConfirm()).isNull();
            // 正常 CHAT 轮：轮数 +1，主回复 LLM 被调用（决策轮之外第二次调用）
            assertThat(conversation.getRoundCount()).isEqualTo(4);
            verify(platformAgentExecutionService).executeSync(any(Agent.class),
                    argThat(task -> task != null
                            && "requirement_chat".equals(task.getContext() == null
                            ? null : task.getContext().get("scene"))));
        }

        @Test
        @DisplayName("口语化意图词不再触发正则：走正常 CHAT LLM 调用")
        void colloquialIntentPhraseGoesThroughNormalChatLlm() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("好的，帮你梳理一下方案思路：");

            clarifyService.sendMessage(CONV_ID, "帮我整理方案吧");

            // 意图词不再触发 pendingClarifyConfirm
            assertThat(conversation.getPendingClarifyConfirm()).isNull();
            assertThat(conversation.getRoundCount()).isEqualTo(4);
            verify(platformAgentExecutionService).executeSync(any(Agent.class),
                    argThat(task -> task != null
                            && "requirement_chat".equals(task.getContext() == null
                            ? null : task.getContext().get("scene"))));
        }

        @Test
        @DisplayName("CHAT 轮 AUTO + LLM 决策搜索：结果注入 CHAT 模板，纯文本回复 payload 携带 webSearch 查验键")
        void chatRoundTriggersWebSearch() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(0);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(false);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                    WebSearchResult.builder().title("OpenMaic 官网")
                            .url("https://open.maic.chat/").snippet("OpenMaic 开放平台官网").build()));
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "你好", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success("你好！", "stop", "llm", 100));
            // 联合决策：AUTO 模式下 LLM 决策 need_search=true，优化词优先（与规则词同词，兼容原断言）
            stubDecisionRound(decisionChatWithSearch("你好"));

            clarifyService.sendMessage(CONV_ID, "你好");

            // 优先使用 LLM 优化词搜索（候选词第一位）
            verify(webSearchService).search(eq("你好"), eq(5));
            // 联网资料注入 CHAT 通用助手模板（主回复轮 = 第 2 次 executeSync）
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService, times(2)).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getAllValues().get(1).getUserPrompt())
                    .contains("AI 助手")
                    .contains("OpenMaic 官网");
            // 纯文本回复也携带 webSearch 查验键（与终稿轮同形态）
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"), eq("你好！"), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"webSearch\"")
                    .contains("OpenMaic 官网")
                    .contains("\"total\":1");
        }

        @Test
        @DisplayName("CHAT 会话开关关闭：不触发联网搜索，payload 保持 NULL")
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
        @DisplayName("CHAT 轮决策解析失败：降级 chat 意图 + AUTO 规则搜索兜底 + 主回复正常（绝不阻塞）")
        void chatRoundDecisionFailure_degradesToChatWithRuleSearch() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(0);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                    WebSearchResult.builder().title("行情速递")
                            .url("https://a.example/1").snippet("摘要").build()));
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "帮我查一下最新行情", 1)));
            // 先注册主回复 any stub，再注册决策轮 stub（后注册优先：决策轮命中坏 JSON → 解析失败降级）
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success("最新行情如下：", "stop", "llm", 100));
            stubDecisionRound("\"这不是合法的决策对象\"");

            clarifyService.sendMessage(CONV_ID, "帮我查一下最新行情");

            // 降级 chat：主回复正常落库，轮数 +1，payload 携带搜索查验键
            assertThat(conversation.getRoundCount()).isEqualTo(1);
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"), eq("最新行情如下："),
                    payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"webSearch\"")
                    .contains("行情速递");
            // AUTO 降级 → 规则搜索兜底（决策不可用不丢搜索机会，词=当前轮消息截断）
            verify(webSearchService).search(eq("帮我查一下最新行情"), eq(5));
            // 决策轮 + 主回复轮各一次
            verify(platformAgentExecutionService, times(2)).executeSync(any(Agent.class), any(AgentTask.class));
        }

        @Test
        @DisplayName("CHAT 轮 AUTO + need_search=true：LLM 优化词在候选首位，优先于规划器规则词")
        void chatRoundAuto_llmQueryTakesPriorityOverPlannerQueries() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(0);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.provider()).thenReturn("bocha");
            when(searchQueryPlannerService.planQueries(anyString()))
                    .thenReturn(List.of("规则候选词"));
            when(webSearchService.search(eq("最新 AI 编程动态"), eq(5))).thenReturn(List.of(
                    WebSearchResult.builder().title("AI 编程最新进展")
                            .url("https://a.example/1").snippet("摘要").build()));
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "你好", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success("最近 AI 编程这么火：", "stop", "llm", 100));
            stubDecisionRound(decisionChatWithSearch("最新 AI 编程动态"));

            clarifyService.sendMessage(CONV_ID, "你好");

            // LLM 优化词命中即停，规划器规则词永远不尝试（候选词顺序降级：LLM 词优先）
            verify(webSearchService).search(eq("最新 AI 编程动态"), eq(5));
            verify(webSearchService, never()).search(eq("规则候选词"), anyInt());
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"), eq("最近 AI 编程这么火："),
                    payloadCaptor.capture());
            assertThat(payloadCaptor.getValue()).contains("\"webSearch\"").contains("AI 编程最新进展");
        }

        @Test
        @DisplayName("CHAT 轮 ALWAYS_ON（开关 true）：每轮搜索，need_search=false 也搜（LLM 只贡献优化词）")
        void chatRoundAlwaysOn_searchesEveryRound() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(0);
            conversation.setWebSearchEnabled(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                    WebSearchResult.builder().title("行情速递")
                            .url("https://a.example/1").snippet("摘要").build()));
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "你好", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success("这是最新行情：", "stop", "llm", 100));
            // ALWAYS_ON 忽略 need_search 的决策结果（false 也搜），仅取 search_query 作优化词
            stubDecisionRound("""
                    {"intent":"chat","intent_reason":"direct_answer","clarification_question":null,
                     "web_search":{"need_search":false,"search_query":"最新行情","reason":"每轮搜索"}}
                    """);

            clarifyService.sendMessage(CONV_ID, "你好");

            verify(webSearchService).search(eq("最新行情"), eq(5));
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"), eq("这是最新行情："),
                    payloadCaptor.capture());
            assertThat(payloadCaptor.getValue()).contains("\"webSearch\"").contains("行情速递");
        }

        @Test
        @DisplayName("CHAT 轮 URL 分离：直取页面正文注入 CHAT 模板，payload 携带 fetched 查验键")
        void chatRoundWithUrl_fetchesPageAndInjects() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(0);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchProperties.getMaxSnippetChars()).thenReturn(200);
            when(webSearchProperties.isUrlFetchEnabled()).thenReturn(true);
            when(webSearchProperties.getUrlFetchMaxPages()).thenReturn(2);
            when(webSearchService.provider()).thenReturn("bocha");
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());
            when(pageFetchService.fetch("https://open.maic.chat/")).thenReturn(WebPageContent.builder()
                    .url("https://open.maic.chat/").ok(true)
                    .title("OpenMaic 官网").text("这里是官网正文内容").build());
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "你好", 1)));
            when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                    .thenReturn(AgentResult.success("好的，这是快速上手手册大纲：", "stop", "llm", 100));
            // 联合决策：AUTO 决策 need_search=true，LLM 优化词为剥离 URL 后的语义关键词
            stubDecisionRound(decisionChatWithSearch("快速上手 操作手册"));

            clarifyService.sendMessage(CONV_ID, "给我一份快速上手 https://open.maic.chat/ 的操作手册");

            // 搜索词不含裸 URL：LLM 优化词在候选首位，直取被提取出的 URL
            verify(pageFetchService).fetch("https://open.maic.chat/");
            // 直取正文（第一手资料）注入 CHAT 模板（主回复轮 = 第 2 次 executeSync）
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService, times(2)).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getAllValues().get(1).getUserPrompt()).contains("这里是官网正文内容");
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
        @DisplayName("CHAT 达上限后意图词不再放行：LLM auto 意图路由替代，50 轮上限统一拒绝")
        void intentAtChatLimitNowRejected() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(50);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);

            assertThatThrownBy(() -> clarifyService.sendMessage(CONV_ID, "整理成方案"))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("自由对话轮数已达上限");
        }

        @Test
        @DisplayName("待确认状态确认卡点「确认」→ 转入 CLARIFY 并清标记，该条消息即澄清首轮（澄清模板 LLM）")
        void confirmCardSelectionSwitchesToClarifyRound() {
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

            // 确认卡点「确认」：通过 selections 快照通道
            clarifyService.sendMessage(CONV_ID, "确认",
                    List.of(createConfirmSelection("确认")));

            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            assertThat(conversation.getRoundCount()).isEqualTo(4);
            ArgumentCaptor<String> userPayloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("user"), eq("确认"),
                    userPayloadCaptor.capture());
            assertThat(userPayloadCaptor.getValue()).contains("\"selections\"");
            ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
            verify(platformAgentExecutionService).executeSync(any(Agent.class), taskCaptor.capture());
            assertThat(taskCaptor.getValue().getUserPrompt()).contains("资深需求分析师");
        }

        @Test
        @DisplayName("CHAT 达 50 轮上限后确认卡点「确认」仍放行：转 CLARIFY 不被上限挡住")
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

            // 确认卡点「确认」（selections 快照通道）：逃生通道，不被 CHAT 50 轮上限挡住
            clarifyService.sendMessage(CONV_ID, "确认",
                    List.of(createConfirmSelection("确认")));

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
        @DisplayName("待确认状态再次输入意图词 → 不再视为确认，清标记继续 CHAT（确认仅通过确认卡）")
        void intentDuringPendingConfirmClearsAndContinuesChat() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            conversation.setPendingClarifyConfirm(true);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            stubChatLlmRound("可以，继续聊。");

            clarifyService.sendMessage(CONV_ID, "整理成方案");

            // 再次意图词不再视为确认（LLM auto 意图路由替代），清标记继续 CHAT
            assertThat(conversation.getPendingClarifyConfirm()).isFalse();
            assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            verify(platformAgentExecutionService).executeSync(any(Agent.class),
                    argThat(task -> task != null
                            && "requirement_chat".equals(task.getContext() == null
                            ? null : task.getContext().get("scene"))));
        }

        @Test
        @DisplayName("create 首条消息含意图词 → 走正常 CHAT LLM 调用（LLM auto 意图路由替代正则）")
        void createIntentPhraseGoesThroughNormalChatLlm() {
            when(conversationService.save(any(RequirementConversation.class))).thenAnswer(inv -> {
                RequirementConversation c = inv.getArgument(0);
                c.setId(CONV_ID);
                return true;
            });
            stubChatLlmRound("好的，帮你梳理一下周报工具方案：");

            clarifyService.create("帮我整理成方案：做一个周报工具", null);

            ArgumentCaptor<RequirementConversation> convCaptor =
                    ArgumentCaptor.forClass(RequirementConversation.class);
            verify(conversationService).save(convCaptor.capture());
            // 新会话始终 CHAT 模式
            assertThat(convCaptor.getValue().getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
            // 正常调 LLM（决策轮 + 主回复轮各一次，不再走意图词正则拦截）
            verify(platformAgentExecutionService, times(2))
                    .executeSync(any(Agent.class), any(AgentTask.class));
        }

        @Test
        @DisplayName("create 缺省初始模式为 CHAT（产品决策：新会话默认自由对话）")
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
        @DisplayName("create：initialMode 已废弃忽略（LLM auto 意图路由取代），传非法值仍按 CHAT 建会")
        void createIgnoresDeprecatedInitialMode() {
            when(conversationService.save(any(RequirementConversation.class))).thenAnswer(inv -> {
                RequirementConversation c = inv.getArgument(0);
                c.setId(CONV_ID);
                return true;
            });
            stubChatLlmRound("你好！");

            clarifyService.create("你好", null, null, "BOGUS");

            ArgumentCaptor<RequirementConversation> captor =
                    ArgumentCaptor.forClass(RequirementConversation.class);
            verify(conversationService).save(captor.capture());
            assertThat(captor.getValue().getMode()).isEqualTo(RequirementClarifyService.MODE_CHAT);
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
        @DisplayName("to-clarify 带附加文本（/planner 命令路径）：先落库 user 消息进上下文，再切 CLARIFY 跑澄清轮")
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
        @DisplayName("命令别名：/plan 与 /task 前缀（含大写）直接转方案澄清，命令本身不落库")
        void plannerCommandAliasesSwitchToClarify() {
            for (String command : List.of("/plan 做周报", "/task 做周报", "/PLAN 做周报")) {
                RequirementConversation conversation = chatConversation();
                when(conversationService.getById(CONV_ID)).thenReturn(conversation);
                when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
                when(messageService.listByConversation(CONV_ID))
                        .thenReturn(List.of(message("user", "做一个报表", 1)));
                when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                        .thenReturn(AgentResult.success(
                                "{\"type\":\"question\",\"message\":\"验收标准是什么？\"}", "stop", "llm", 100));

                clarifyService.sendMessage(CONV_ID, command);

                // 跳过决策：命令不落库，直接切 CLARIFY（模式断言在循环内；mock 断言汇总在循环外）
                assertThat(conversation.getMode()).isEqualTo(RequirementClarifyService.MODE_CLARIFY);
            }
            // 附加文本作为 user 消息进上下文（3 次）；命令本身从不落库
            verify(messageService, times(3)).addMessage(CONV_ID, "user", "做周报", null);
            verify(messageService, never()).addMessage(eq(CONV_ID), eq("user"),
                    argThat(content -> content != null && content.startsWith("/")), any());
            // 3 条命令各触发一次澄清轮（requirements_clarify 澄清模板），全程零决策轮
            verify(platformAgentExecutionService, times(3)).executeSync(any(Agent.class), any(AgentTask.class));
            verify(platformAgentExecutionService, never()).executeSync(any(Agent.class),
                    argThat(task -> task != null
                            && DECISION_SCENE.equals(task.getContext() == null
                            ? null : task.getContext().get("scene"))));
        }

        @Test
        @DisplayName("CHAT 容错双模：LLM 输出 structured 追问 → payload 落库出推荐卡片，模式仍 CHAT（未配置搜索参数时不触发搜索）")
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
        @DisplayName("CHAT 容错双模：freeform JSON / 非结构化输出仍按纯文本落库（payload NULL，零破坏）")
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
        //  意图词扩充 + 确认卡结构化
        // ════════════════════════════════════════════════════════

        @Test
        @DisplayName("联合决策 clarify 意图：单条确认卡落库（题面=LLM 澄清问题），主回复 LLM 不调用")
        void llmClarifyIntent_setsPendingConfirmAndSendsCard() {
            RequirementConversation conversation = chatConversation();
            conversation.setRoundCount(3);
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "新建个计划吧", 1)));
            // 前置联合决策：intent=clarify + 澄清问题（只注册决策轮 stub；主回复轮若被调用将无 stub 而失败）
            stubDecisionRound("""
                    {"intent":"clarify","intent_reason":"task_oriented",
                     "clarification_question":"你希望这套方案覆盖哪些核心场景？",
                     "web_search":{"need_search":false,"search_query":null,"reason":"转入澄清无需搜索"}}
                    """);

            clarifyService.sendMessage(CONV_ID, "新建个计划吧");

            assertThat(conversation.getPendingClarifyConfirm()).isTrue();
            // 决策前置：轮数 +1 但不走主回复 LLM（确认卡单条落库即返回）
            assertThat(conversation.getRoundCount()).isEqualTo(4);
            // 决策轮恰一次；主回复轮零调用（intent=clarify 直接返回）
            verify(platformAgentExecutionService, times(1)).executeSync(any(Agent.class),
                    argThat(task -> task != null
                            && DECISION_SCENE.equals(task.getContext() == null
                            ? null : task.getContext().get("scene"))));
            verify(platformAgentExecutionService, never()).executeSync(any(Agent.class),
                    argThat(task -> task != null
                            && "requirement_chat".equals(task.getContext() == null
                            ? null : task.getContext().get("scene"))));
            // 确认卡单条落库：内容承载澄清问题，payload 题面 = 澄清问题
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq(ConfirmCardProtocol.CONFIRM_ASK_TEXT + "\n\n你希望这套方案覆盖哪些核心场景？"),
                    payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"mode\":\"structured\"")
                    .contains("\"confirm-switch\"")
                    .contains("你希望这套方案覆盖哪些核心场景？")
                    .contains("确认").contains("取消")
                    .doesNotContain("recommended");
        }

        @Test
        @DisplayName("联合决策 clarify 意图确认卡 payload：仅 1 题 2 选项，allowCustom=false 且无 recommended 标记")
        void llmClarifyIntent_confirmPayloadHasTwoOptionsWithoutRecommended() throws Exception {
            RequirementConversation conversation = chatConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
            when(messageService.listByConversation(CONV_ID))
                    .thenReturn(List.of(message("user", "给一个方案", 1)));
            stubDecisionRound("""
                    {"intent":"clarify","intent_reason":"need_clarification",
                     "clarification_question":"方案的第一期范围怎么定？",
                     "web_search":{"need_search":false,"search_query":null,"reason":"澄清轮不搜"}}
                    """);

            clarifyService.sendMessage(CONV_ID, "给一个方案");

            assertThat(conversation.getPendingClarifyConfirm()).isTrue();
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    eq(ConfirmCardProtocol.CONFIRM_ASK_TEXT + "\n\n方案的第一期范围怎么定？"),
                    payloadCaptor.capture());
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(payloadCaptor.getValue());
            assertThat(root.get("mode").asText()).isEqualTo("structured");
            com.fasterxml.jackson.databind.JsonNode questions = root.get("questions");
            assertThat(questions).hasSize(1);
            com.fasterxml.jackson.databind.JsonNode question = questions.get(0);
            assertThat(question.get("id").asText()).isEqualTo("confirm-switch");
            // 题面直通 LLM 澄清问题（不再使用固定默认文案）
            assertThat(question.get("text").asText()).isEqualTo("方案的第一期范围怎么定？");
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
        @DisplayName("联合决策 clarify 意图覆盖：多短语输入均触发决策轮并进入待确认（LLM 决策驱动，非正则匹配）")
        void llmClarifyIntentPhrases_allEnterPendingConfirm() {
            for (String phrase : List.of("给一个方案", "帮我总结一下", "新建个任务吧", "帮我生成计划", "来一个计划")) {
                RequirementConversation conversation = chatConversation();
                when(conversationService.getById(CONV_ID)).thenReturn(conversation);
                when(plannerAgentPicker.pick(isNull())).thenReturn(llmPlanner());
                when(messageService.listByConversation(CONV_ID))
                        .thenReturn(List.of(message("user", phrase, 1)));
                // 只注册决策轮 stub：clarify 意图直接返回，主回复轮若被调用将无 stub 而失败
                stubDecisionRound("""
                        {"intent":"clarify","intent_reason":"task_oriented",
                         "clarification_question":"我们从这个方案的目标说起，你希望达成什么？",
                         "web_search":{"need_search":false,"search_query":null,"reason":"澄清轮不搜"}}
                        """);

                clarifyService.sendMessage(CONV_ID, phrase);

                assertThat(conversation.getPendingClarifyConfirm()).as(phrase).isTrue();
            }
            // 5 轮各触发一次决策调用；主回复轮零调用（全部 clarify 直接返回）
            verify(platformAgentExecutionService, times(5)).executeSync(any(Agent.class),
                    argThat(task -> task != null
                            && DECISION_SCENE.equals(task.getContext() == null
                            ? null : task.getContext().get("scene"))));
            verify(platformAgentExecutionService, never()).executeSync(any(Agent.class),
                    argThat(task -> task != null
                            && "requirement_chat".equals(task.getContext() == null
                            ? null : task.getContext().get("scene"))));
        }

        @Test
        @DisplayName("防误触：含裸词「任务」的普通提问不触发确认，正常走 CHAT 轮")
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
        @DisplayName("确认卡点「确认」：经 selections 快照判定转入 CLARIFY（提交文本非确认词开头也能切换）")
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
        @DisplayName("确认卡点「取消」：清标记继续 CHAT（不调澄清模板）")
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
        //  联网搜索：多轮触发 + payload 查验
        // ════════════════════════════════════════════════════════

        @Test
        @DisplayName("多轮搜索：CLARIFY 第 2 轮也触发搜索，assistant payload 含 webSearch 查验键")
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
        @DisplayName("修复：确认卡切入方案时搜索词不用卡片提交文本，回退历史主题消息")
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
        @DisplayName("修复：确认后历史无可回退主题消息时不发起搜索（不落查验条）")
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
        @DisplayName("搜索异常查验：search 抛异常降级 failed=true 落 payload，澄清主流程不阻断")
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
        @DisplayName("URL 分离：搜索词用剥离 URL 的语义文本，直取页面置顶作来源")
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
        @DisplayName("纯 URL 消息：搜索词回退域名，直取照常发起")
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
        @DisplayName("URL 直取失败：不进来源列表，失败记录落 payload 可查验")
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
        @DisplayName("直取全部失败：域名前置增强搜索词（检索站点公开资料）")
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
        @DisplayName("SPA 空壳元数据兜底：metaOnly 直取进来源且 payload 落标记")
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
        @DisplayName("URL 直取开关关闭：不发起抓取，回退纯搜索引擎行为")
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

        // ════════════════════════════════════════════════════════
        //  联网搜索：查询规划 + 顺序降级
        // ════════════════════════════════════════════════════════

        @Test
        @DisplayName("规划器多候选词：顺序降级首命中即停，payload 落全部已尝试词")
        void doRound_multipleCandidates_sequentialFallbackStopsOnHit() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.provider()).thenReturn("bocha");
            when(searchQueryPlannerService.planQueries(anyString()))
                    .thenReturn(List.of("快速学习Python", "Python 项目搭建教程"));
            // 首候选词零结果 → 降级次词命中
            when(webSearchService.search(eq("快速学习Python"), eq(5))).thenReturn(List.of());
            when(webSearchService.search(eq("Python 项目搭建教程"), eq(5))).thenReturn(List.of(
                    WebSearchResult.builder().title("Python 实战教程")
                            .url("https://p.example/1").snippet("摘要").build()));
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID,
                    "能否给我提供一份快速学习Python + 快速搭建项目的完整方案");

            // 顺序降级：两词各试一次，命中即停（共 2 次调用）
            verify(webSearchService, times(2)).search(anyString(), anyInt());
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    anyString(), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"webSearch\"")
                    .contains("\"queries\":[\"快速学习Python\",\"Python 项目搭建教程\"]")
                    .contains("\"total\":1")
                    .contains("Python 实战教程");
        }

        @Test
        @DisplayName("候选词全零结果：total=0 不静默放弃，queries 完整落 payload 可查验")
        void doRound_allCandidatesEmpty_totalZeroWithQueriesInPayload() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.provider()).thenReturn("bocha");
            when(searchQueryPlannerService.planQueries(anyString()))
                    .thenReturn(List.of("快速学习Python", "Python 项目搭建"));
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of());
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID,
                    "能否给我提供一份快速学习Python + 快速搭建项目的完整方案");

            // 两个候选词都尝试过（零结果不放弃），结局可查验
            verify(webSearchService, times(2)).search(anyString(), anyInt());
            ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
            verify(messageService).addMessage(eq(CONV_ID), eq("assistant"),
                    anyString(), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue())
                    .contains("\"webSearch\"")
                    .contains("\"queries\":[\"快速学习Python\",\"Python 项目搭建\"]")
                    .contains("\"total\":0");
        }

        @Test
        @DisplayName("规划器无产出：兜底规则截断提取搜索词（旧行为不丢）")
        void doRound_plannerEmpty_fallsBackToTruncatedKeyword() {
            RequirementConversation conversation = activeConversation();
            when(conversationService.getById(CONV_ID)).thenReturn(conversation);
            when(webSearchProperties.getQueryKeywordLimit()).thenReturn(40);
            when(webSearchProperties.getMaxResults()).thenReturn(5);
            when(webSearchService.provider()).thenReturn("bocha");
            when(searchQueryPlannerService.planQueries(anyString())).thenReturn(List.of());
            when(webSearchService.search(anyString(), anyInt())).thenReturn(List.of(
                    WebSearchResult.builder().title("报表方案参考")
                            .url("https://a.example/1").snippet("摘要").build()));
            stubLlmRound("{\"type\":\"question\",\"message\":\"验收标准是什么？\"}");

            clarifyService.sendMessage(CONV_ID, "做一个报表");

            // 规划器空产出 → 兜底前 40 字截断（与改造前行为一致）
            verify(webSearchService).search(eq("做一个报表"), eq(5));
        }
    }
}
