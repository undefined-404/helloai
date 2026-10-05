package com.helloai.core.agent.command;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskRunningSpecPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
import com.helloai.core.agent.output.ExecutionOutputParser;
import com.helloai.core.agent.output.ParsedOutput;
import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.core.agent.port.TaskTimelinePort;

/**
 * {@code ExecutionResultHandler} 单测。
 *
 * <p><b>2026-10-01 W10 改造</b>：被测类不再直接依赖 {@code task} 域（{@code SubTaskService} /
 * {@code TaskRunningSpecService} / {@code SubTask} 实体），改为依赖 {@code agent.port} 三个端口
 * （{@link SubTaskQueryPort} / {@link SubTaskCommandPort} / {@link TaskRunningSpecPort}）。
 * 断言<b>逐条保留</b>，仅把「mock 服务 + 捕获实体」换成「mock 端口 + 捕获 context Map」——
 * 回写内容（{@code lastExecution} 载荷）与状态推进调用（{@code submit} / {@code block}）口径不变。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionResultHandler")
class ExecutionResultHandlerTest {

    @Mock
    private SubTaskQueryPort subTaskQueryPort;

    @Mock
    private SubTaskCommandPort subTaskCommandPort;

    @Mock
    private TaskTimelinePort taskTimelinePort;

    @Mock
    private ExternalAgentFailureTracker failureTracker;

    @Mock
    private AgentService agentService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private com.helloai.core.agent.service.ConversationService conversationService;

    @Mock
    private com.helloai.core.agent.service.ExecutionArtifactService executionArtifactService;

    // §6.93 后 ExecutionResultHandler 构造器新增字段；@InjectMocks 缺 mock 会注入 null 导致 NPE
    @Mock
    private TaskRunningSpecPort taskRunningSpecPort;

    @Mock
    private ExecutionOutputParser executionOutputParser;

    // Phase 1 Step 3：执行会话服务（终态 COMPLETED/FAILED；@InjectMocks 缺 mock 会注入 null 导致 NPE）
    @Mock
    private AgentSessionService agentSessionService;

    // 804 归属校验：失败回写前读执行记录状态（@InjectMocks 缺 mock 会注入 null 导致 NPE）
    @Mock
    private com.helloai.core.agent.service.AgentExecutionRecordService agentExecutionRecordService;

    @InjectMocks
    private ExecutionResultHandler executionResultHandler;

    @BeforeEach
    void setUp() {
        // 方案3 产出物化解析：默认空输出（走原文分支），避免 mock 返回 null 触发 NPE
        lenient().when(executionOutputParser.parse(any(), any())).thenReturn(ParsedOutput.empty());
    }

    private static SubTaskSnapshot snapshot(Long id, Long taskId, SubTaskStatus status) {
        return SubTaskSnapshot.builder()
                .id(id)
                .taskId(taskId)
                .status(status)
                .title("sub-task-" + id)
                .build();
    }

    @Test
    @DisplayName("成功结果回写 context、推进 REVIEW 并记录时间线")
    void shouldHandleSuccess() {
        when(subTaskQueryPort.findById(22L))
                .thenReturn(snapshot(22L, 33L, SubTaskStatus.IN_PROGRESS));

        AgentResult result = AgentResult.success("done", "stop", "ApiKeyAgentExecutor", 12);

        executionResultHandler.handleSuccess(22L, 11L, result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskCommandPort).updateContext(eq(22L), ctxCaptor.capture());
        Map<String, Object> context = ctxCaptor.getValue();
        assertThat(context).containsKey("lastExecution");
        @SuppressWarnings("unchecked")
        Map<String, Object> lastExecution = (Map<String, Object>) context.get("lastExecution");
        assertThat(lastExecution)
                .containsEntry("agentId", 11L)
                .containsEntry("success", true)
                .containsEntry("executor", "ApiKeyAgentExecutor")
                .containsEntry("finishReason", "stop")
                .containsEntry("tokens", 12)
                .containsEntry("output", "done");

        verify(subTaskCommandPort).submit(22L);
        // Phase 1 Step 3：执行会话终态 COMPLETED（turn=1+rework+attempt=1）
        verify(agentSessionService).complete(22L, 11L, 1);
        verify(taskTimelinePort).recordEvent(
                eq(33L), eq(22L), eq("sub_task_execute_submit"), eq(AgentRole.EXECUTOR), eq(11L),
                argThat((Map<String, Object> payload) ->
                        Boolean.TRUE.equals(payload.get("success"))
                                && "ApiKeyAgentExecutor".equals(payload.get("executor"))
                                && payload.containsKey("tokens")
                                && "INTERNAL".equals(payload.get("source"))));
    }

    @Test
    @DisplayName("失败结果回写 context、推进 BLOCKED 并记录失败时间线")
    void shouldHandleFailure() {
        when(subTaskQueryPort.findById(22L))
                .thenReturn(snapshot(22L, 33L, SubTaskStatus.IN_PROGRESS));

        executionResultHandler.handleFailure(22L, 11L, new RuntimeException("boom"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctxCaptor = ArgumentCaptor.forClass(Map.class);
        verify(subTaskCommandPort).updateContext(eq(22L), ctxCaptor.capture());
        Map<String, Object> context = ctxCaptor.getValue();
        assertThat(context).containsKey("lastExecution");
        @SuppressWarnings("unchecked")
        Map<String, Object> lastExecution = (Map<String, Object>) context.get("lastExecution");
        assertThat(lastExecution)
                .containsEntry("agentId", 11L)
                .containsEntry("success", false)
                .containsEntry("error", "boom");

        // W10：block(subTaskId, null, null) 逐字等价于 SubTaskService#block(Long)
        verify(subTaskCommandPort).block(22L, null, null);
        // Phase 1 Step 3：执行会话终态 FAILED（error 摘要；turn=1）
        verify(agentSessionService).fail(22L, 11L, 1, "boom");
        verify(taskTimelinePort).recordEvent(
                eq(33L), eq(22L), eq("sub_task_execute_failed"), eq(AgentRole.EXECUTOR), eq(11L),
                argThat((Map<String, Object> payload) ->
                        Boolean.FALSE.equals(payload.get("success"))
                                && "boom".equals(payload.get("error"))
                                && "INTERNAL".equals(payload.get("source"))));
    }

    @Test
    @DisplayName("B 方案配套：失败回写携带 thinking ⇒ 仍落 sub_task_execute_thinking 对话消息，且失败语义不变（block）")
    void shouldPersistThinkingOnFailure() {
        when(subTaskQueryPort.findById(22L))
                .thenReturn(snapshot(22L, 33L, SubTaskStatus.IN_PROGRESS));

        executionResultHandler.handleFailure(22L, 11L, "模型的思维链",
                new RuntimeException("agent_runtime_empty_output: 模型返回空正文"));

        // 思维链落同一通道（toolName=sub_task_execute_thinking）
        verify(conversationService).addMessage(eq(22L), eq(11L), eq("assistant"), eq("agent"),
                eq("模型的思维链"), eq("sub_task_execute_thinking"));
        // 失败原因照常落 sub_task_execute_failed
        verify(conversationService).addMessage(eq(22L), eq(11L), eq("assistant"), eq("agent"),
                any(), eq("sub_task_execute_failed"));
        // B 不改变失败语义：仍 block、不 submit
        verify(subTaskCommandPort).block(22L, null, null);
        verify(subTaskCommandPort, never()).submit(any());
    }

    @Test
    @DisplayName("P2-2: 补偿任务将 subTask 推进到 BLOCKED 后，handleSuccess 不应复活到 REVIEW")
    void shouldNotReviveSubTaskWhenStatusIsBlocked() {
        // 已被补偿任务推进
        when(subTaskQueryPort.findById(22L))
                .thenReturn(snapshot(22L, 33L, SubTaskStatus.BLOCKED));

        AgentResult result = AgentResult.success("done", "stop", "ApiKeyAgentExecutor", 12);

        executionResultHandler.handleSuccess(22L, 11L, result);

        // 不应推进到 REVIEW
        verify(subTaskCommandPort, never()).submit(22L);
        // 不应覆写 context
        verify(subTaskCommandPort, never()).updateContext(any(), any());
        // 应记录 "结果被丢弃" 事件
        verify(taskTimelinePort).recordEvent(
                eq(33L),
                eq(22L),
                eq("sub_task_execute_result_discarded"),
                eq(AgentRole.EXECUTOR),
                eq(11L),
                anyMap());
    }

    @Test
    @DisplayName("P2-3: handleFailure 对非 IN_PROGRESS 状态的子任务不应调用 block，并丢弃结果记录 timeline")
    void shouldNotBlockWhenStatusIsNotInProgress() {
        // 已经是 BLOCKED
        when(subTaskQueryPort.findById(22L))
                .thenReturn(snapshot(22L, 33L, SubTaskStatus.BLOCKED));

        executionResultHandler.handleFailure(22L, 11L, new RuntimeException("boom"));

        // 不应再次 block
        verify(subTaskCommandPort, never()).block(any(), any(), any());
        // 不应修改 context
        verify(subTaskCommandPort, never()).updateContext(any(), any());
        // 走 "结果被丢弃" 时间线（Phase 2B 后由 handleReport() 统一接管非 IN_PROGRESS 拒绝）
        verify(taskTimelinePort).recordEvent(
                eq(33L), eq(22L), eq("sub_task_execute_result_discarded"), eq(AgentRole.EXECUTOR), eq(11L),
                argThat((Map<String, Object> payload) ->
                        "subtask_status_not_in_progress".equals(payload.get("reason"))
                                && "BLOCKED".equals(payload.get("currentStatus"))
                                && Boolean.FALSE.equals(payload.get("success"))
                                && "INTERNAL".equals(payload.get("source"))));
    }

    @Test
    @DisplayName("804: 失败回写带 recordId 且记录非 RUNNING（双消费输家）→ 不 block，落 skipped 时间线")
    void shouldNotBlockWhenRecordNotOwned() {
        when(subTaskQueryPort.findById(22L))
                .thenReturn(snapshot(22L, 33L, SubTaskStatus.IN_PROGRESS));
        // 记录已被他路接管/终结（SUCCESS）→ 本轮非持有者，不得据此阻塞赢家
        com.helloai.core.agent.entity.AgentExecutionRecord record =
                new com.helloai.core.agent.entity.AgentExecutionRecord();
        record.setId(44L);
        record.setStatus(com.helloai.common.constant.ExecutionStatus.SUCCESS);
        when(agentExecutionRecordService.getById(44L)).thenReturn(record);

        executionResultHandler.handleFailure(22L, 11L, 44L, new RuntimeException("并发修改，请重试"));

        verify(subTaskCommandPort, never()).block(any(), any(), any());
        verify(taskTimelinePort).recordEvent(
                eq(33L), eq(22L), eq("sub_task_report_blocked_skipped"), eq(AgentRole.EXECUTOR), eq(11L),
                argThat((Map<String, Object> payload) ->
                        "record_not_running_owned_by_other".equals(payload.get("reason"))
                                && Long.valueOf(44L).equals(payload.get("recordId"))));
    }

    @Test
    @DisplayName("804: 失败回写带 recordId 且记录仍 RUNNING（本消费持有）→ 正常 block")
    void shouldBlockWhenRecordStillRunning() {
        when(subTaskQueryPort.findById(22L))
                .thenReturn(snapshot(22L, 33L, SubTaskStatus.IN_PROGRESS));
        com.helloai.core.agent.entity.AgentExecutionRecord record =
                new com.helloai.core.agent.entity.AgentExecutionRecord();
        record.setId(44L);
        record.setStatus(com.helloai.common.constant.ExecutionStatus.RUNNING);
        when(agentExecutionRecordService.getById(44L)).thenReturn(record);

        executionResultHandler.handleFailure(22L, 11L, 44L, new RuntimeException("boom"));

        verify(subTaskCommandPort).block(22L, null, null);
    }
}
