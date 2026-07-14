package com.helloai.core.agent.mqconsumer;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.ExecutionCommandCreatedEvent;
import com.helloai.core.service.AgentExecutionRecordService;
import com.helloai.core.service.AgentService;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.execution.SubTaskExecutionService;
import com.helloai.core.service.SubTaskService;
import com.helloai.core.service.TaskTimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
            subTask.setAssignedAgent(99L); // 与 command.agentId=11L 不一致

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

    private static SubTask subTask() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgent(11L);
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