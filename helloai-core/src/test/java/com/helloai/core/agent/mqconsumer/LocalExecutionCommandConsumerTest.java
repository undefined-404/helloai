package com.helloai.core.agent.mqconsumer;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.AgentRuntime;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.shared.event.ExecutionCommandCreatedEvent;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.service.SubTaskExecutionService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocalExecutionCommandConsumer")
class LocalExecutionCommandConsumerTest {

    @Mock
    private SubTaskExecutionService subTaskExecutionService;

    @Mock
    private AgentExecutionRecordService agentExecutionRecordService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentService agentService;

    @Mock
    private ExecutionResultHandler executionResultHandler;

    @Mock
    private AgentRuntime agentRuntime;

    @InjectMocks
    private LocalExecutionCommandConsumer localExecutionCommandConsumer;

    @Nested
    @DisplayName("分层消费正常路径")
    class HappyPath {

        @Test
        @DisplayName("should run startIfNeeded → markRunning → executeOnce → handleSuccess → markSuccess")
        void shouldConsumeWhenCommandCreatedEventArrives() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.ASSIGNED);
            Agent agent = agent();
            AgentResult ok = AgentResult.builder().success(true).build();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markSuccess(44L)).thenReturn(true);
            when(subTaskExecutionService.executeOnce(same(subTask), same(agent))).thenReturn(ok);

            ExecutionCommand command = baseCommand();
            localExecutionCommandConsumer.onCommandCreated(new ExecutionCommandCreatedEvent(command));

            // 验证分层调用顺序
            verify(subTaskExecutionService).startIfNeeded(22L, SubTaskStatus.ASSIGNED);
            verify(agentExecutionRecordService).markRunning(44L);
            verify(taskTimelineService).recordEvent(
                    33L, 22L, "sub_task_execution_command_consume", AgentRole.EXECUTOR, 11L,
                    Map.of(
                            "trigger", "assigned",
                            "recordId", 44L,
                            "eventId", "evt-1",
                            "accessType", "API_KEY_LLM"));
            verify(taskTimelineService).recordEvent(
                    33L, 22L, "sub_task_execute_start", AgentRole.EXECUTOR, 11L,
                    Map.of("executor", "platform"));
            verify(subTaskExecutionService).executeOnce(subTask, agent);
            verify(executionResultHandler).handleReport(argThat((com.helloai.core.agent.command.ExecutionResultReport r) ->
                    r.getSubTaskId() != null && r.getSubTaskId() == 22L
                            && r.getAgentId() != null && r.getAgentId() == 11L
                            && r.isSuccess()
                            && "INTERNAL".equals(r.getSource())
                            && "evt-1".equals(r.getIdempotencyKey())));
            verify(agentExecutionRecordService).markSuccess(44L);
            // 不应回写失败
            verify(executionResultHandler, never()).handleReport(argThat((com.helloai.core.agent.command.ExecutionResultReport r) -> !r.isSuccess()));
            verify(agentExecutionRecordService, never()).markFailed(any(), any());
        }

        @Test
        @DisplayName("should call handleFailure + markFailed when executeOnce throws")
        void shouldMarkFailedWhenConsumeThrowsException() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markFailed(44L, "exec failed")).thenReturn(true);
            doThrow(new BizException("exec failed"))
                    .when(subTaskExecutionService).executeOnce(same(subTask), same(agent));

            ExecutionCommand command = baseCommand();
            localExecutionCommandConsumer.consume(command);

            // 验证异常路径
            verify(subTaskExecutionService).executeOnce(subTask, agent);
            verify(taskTimelineService).recordEvent(
                    33L, 22L, "sub_task_llm_call_failed", AgentRole.EXECUTOR, 11L,
                    Map.of("agentId", 11L, "error", "exec failed"));
            verify(executionResultHandler).handleReport(argThat((com.helloai.core.agent.command.ExecutionResultReport r) ->
                    r.getSubTaskId() != null && r.getSubTaskId() == 22L
                            && r.getAgentId() != null && r.getAgentId() == 11L
                            && !r.isSuccess()
                            && "exec failed".equals(r.getError())
                            && "evt-1".equals(r.getIdempotencyKey())));
            verify(agentExecutionRecordService).markFailed(44L, "exec failed");
            // 不应回写成功
            verify(executionResultHandler, never()).handleReport(argThat((com.helloai.core.agent.command.ExecutionResultReport r) -> r.isSuccess()));
            verify(agentExecutionRecordService, never()).markSuccess(any());
        }
    }

    @Nested
    @DisplayName("分层消费跳过路径")
    class SkipPath {

        @Test
        @DisplayName("should skip execution when markRunning returns false")
        void shouldSkipExecutionWhenMarkRunningReturnsFalse() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.IN_PROGRESS);
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(false);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService).markRunning(44L);
            verify(subTaskExecutionService, never()).executeOnce(any(), any());
            verify(executionResultHandler, never()).handleSuccess(any(), any(), any());
            verify(executionResultHandler, never()).handleFailure(any(), any(), any());
        }

        @Test
        @DisplayName("should skip when startIfNeeded throws (subTask in BLOCKED)")
        void shouldSkipWhenStartIfNeededThrows() {
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.BLOCKED);
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            doThrow(new BizException("子任务状态不允许执行"))
                    .when(subTaskExecutionService).startIfNeeded(22L, SubTaskStatus.BLOCKED);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(subTaskExecutionService).startIfNeeded(22L, SubTaskStatus.BLOCKED);
            verify(taskTimelineService).recordEvent(
                    eq(33L), eq(22L), eq("sub_task_execution_command_consume_skipped"),
                    eq(AgentRole.EXECUTOR), eq(11L), any());
            // 不应继续执行后续步骤
            verify(agentExecutionRecordService, never()).markRunning(any());
            verify(subTaskExecutionService, never()).executeOnce(any(), any());
            verify(executionResultHandler, never()).handleSuccess(any(), any(), any());
            verify(executionResultHandler, never()).handleFailure(any(), any(), any());
        }

        @Test
        @DisplayName("should skip when subTask does not exist")
        void shouldSkipWhenSubTaskNotExist() {
            when(subTaskService.getById(22L)).thenReturn(null);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(subTaskExecutionService, never()).startIfNeeded(any(), any());
            verify(agentExecutionRecordService, never()).markRunning(any());
            verify(subTaskExecutionService, never()).executeOnce(any(), any());
        }

        @Test
        @DisplayName("should skip when agentId does not match assignedAgent")
        void shouldSkipWhenAgentIdMismatch() {
            SubTask subTask = subTask();
            subTask.setAssignedAgentId(99L); // 与 command.agentId=11L 不一致

            when(subTaskService.getById(22L)).thenReturn(subTask);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(subTaskExecutionService, never()).startIfNeeded(any(), any());
            verify(agentExecutionRecordService, never()).markRunning(any());
            verify(subTaskExecutionService, never()).executeOnce(any(), any());
        }

        @Test
        @DisplayName("should skip when agent does not exist")
        void shouldSkipWhenAgentNotExist() {
            SubTask subTask = subTask();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(null);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(subTaskExecutionService, never()).startIfNeeded(any(), any());
            verify(agentExecutionRecordService, never()).markRunning(any());
            verify(subTaskExecutionService, never()).executeOnce(any(), any());
        }
    }

    @Nested
    @DisplayName("Phase 0 C3 双轨灰度路由")
    class RuntimeRoutePath {

        @Test
        @DisplayName("should run runtime path with record CAS + timeline when gray hits")
        void shouldRouteToRuntimeAndMarkSuccess() {
            enableRuntimeRoute(49); // taskId=33 → 33 % 100 = 33 < 49 → 命中路由
            SubTask subTask = subTask();
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markSuccess(44L)).thenReturn(true);
            when(agentRuntime.execute(any(AgentContext.class)))
                    .thenReturn(AgentExecutionResult.builder()
                            .status(ExecutionStatus.SUCCESS)
                            .output("out")
                            .build());

            localExecutionCommandConsumer.consume(baseCommand());

            // AgentContext 与 B2 埋点同源：run-{taskId}-1 / turn=1 / step=0
            verify(agentRuntime).execute(argThat(ctx ->
                    ctx != null
                            && "run-33-1".equals(ctx.getRunId())
                            && ctx.getTaskId() != null && ctx.getTaskId() == 33L
                            && ctx.getSubTaskId() != null && ctx.getSubTaskId() == 22L
                            && ctx.getTurn() == 1
                            && ctx.getStep() == 0
                            && ctx.getAgentId() != null && ctx.getAgentId() == 11L));
            // record CAS 补齐（旧直连独有职责，路由层代理执行）
            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentExecutionRecordService).markSuccess(44L);
            verify(agentExecutionRecordService, never()).markFailed(any(), any());
            // timeline 观察点（route 标记区分路径）
            verify(taskTimelineService).recordEvent(
                    eq(33L), eq(22L), eq("sub_task_execution_command_consume"),
                    eq(AgentRole.EXECUTOR), eq(11L),
                    argThat((Map<String, Object> m) -> "agent_runtime".equals(m.get("route"))));
            verify(taskTimelineService).recordEvent(
                    33L, 22L, "sub_task_execute_start", AgentRole.EXECUTOR, 11L,
                    Map.of("executor", "agent_runtime"));
            // 旧直连编排不再执行（状态推进 / 执行 / 回写交给 Runtime 实现内部）
            verify(subTaskExecutionService, never()).startIfNeeded(any(), any());
            verify(subTaskExecutionService, never()).executeOnce(any(), any());
            verify(executionResultHandler, never()).handleReport(any());
        }

        @Test
        @DisplayName("should markFailed when runtime returns FAILED")
        void shouldRouteToRuntimeAndMarkFailed() {
            enableRuntimeRoute(49);
            SubTask subTask = subTask();
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markFailed(44L, "run failed")).thenReturn(true);
            when(agentRuntime.execute(any(AgentContext.class)))
                    .thenReturn(AgentExecutionResult.builder()
                            .status(ExecutionStatus.FAILED)
                            .output("run failed")
                            .build());

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentExecutionRecordService).markFailed(44L, "run failed");
            verify(agentExecutionRecordService, never()).markSuccess(any());
            // Runtime 失败同样不进入旧直连编排
            verify(subTaskExecutionService, never()).startIfNeeded(any(), any());
            verify(executionResultHandler, never()).handleReport(any());
        }

        @Test
        @DisplayName("should skip runtime execution when markRunning returns false")
        void shouldSkipWhenMarkRunningFalseOnRuntimePath() {
            enableRuntimeRoute(49);
            SubTask subTask = subTask();
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(false);

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentExecutionRecordService).markRunning(44L);
            verify(agentRuntime, never()).execute(any(AgentContext.class));
            verify(agentExecutionRecordService, never()).markSuccess(any());
            verify(agentExecutionRecordService, never()).markFailed(any(), any());
            verify(subTaskExecutionService, never()).startIfNeeded(any(), any());
        }

        @Test
        @DisplayName("should keep legacy path when taskId % 100 >= grayPercent (33 vs 33)")
        void shouldNotRouteWhenTaskIdExceedsGrayPercent() {
            enableRuntimeRoute(33); // taskId=33 → 33 % 100 = 33 不 < 33 → 不命中路由
            SubTask subTask = subTask();
            subTask.setStatus(SubTaskStatus.ASSIGNED);
            Agent agent = agent();

            when(subTaskService.getById(22L)).thenReturn(subTask);
            when(agentService.getById(11L)).thenReturn(agent);
            when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
            when(agentExecutionRecordService.markSuccess(44L)).thenReturn(true);
            when(subTaskExecutionService.executeOnce(same(subTask), same(agent)))
                    .thenReturn(AgentResult.builder().success(true).build());

            localExecutionCommandConsumer.consume(baseCommand());

            verify(agentRuntime, never()).execute(any(AgentContext.class));
            // 走旧直连：startIfNeeded + executeOnce
            verify(subTaskExecutionService).startIfNeeded(22L, SubTaskStatus.ASSIGNED);
            verify(subTaskExecutionService).executeOnce(subTask, agent);
        }
    }

    private void enableRuntimeRoute(int grayPercent) {
        ReflectionTestUtils.setField(localExecutionCommandConsumer, "grayPercent", grayPercent);
        ReflectionTestUtils.setField(localExecutionCommandConsumer, "agentRuntimes", List.of(agentRuntime));
    }

    private static SubTask subTask() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgentId(11L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        return subTask;
    }

    private static Agent agent() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setName("test-agent");
        return agent;
    }

    private static ExecutionCommand baseCommand() {
        return ExecutionCommand.builder()
                .recordId(44L)
                .eventId("evt-1")
                .subTaskId(22L)
                .agentId(11L)
                .trigger("assigned")
                .accessType(AgentAccessType.API_KEY_LLM)
                .build();
    }
}