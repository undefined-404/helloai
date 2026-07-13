package com.helloai.core.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.ExecutionCommandCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

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

    @InjectMocks
    private LocalExecutionCommandConsumer localExecutionCommandConsumer;

    @Test
    @DisplayName("should consume event and mark success")
    void shouldConsumeWhenCommandCreatedEventArrives() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        when(subTaskService.getById(22L)).thenReturn(subTask);

        ExecutionCommand command = ExecutionCommand.builder()
                .recordId(44L)
                .eventId("evt-1")
                .subTaskId(22L)
                .agentId(11L)
                .trigger("assigned")
                .accessType(AgentAccessType.API_KEY_LLM)
                .build();

        when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
        when(agentExecutionRecordService.markSuccess(44L)).thenReturn(true);
        localExecutionCommandConsumer.onCommandCreated(new ExecutionCommandCreatedEvent(command));

        verify(agentExecutionRecordService).markRunning(44L);
        verify(taskTimelineService).recordEvent(
                33L, 22L, "sub_task_execution_command_consume", AgentRole.EXECUTOR, 11L,
                Map.of(
                        "trigger", "assigned",
                        "recordId", 44L,
                        "eventId", "evt-1",
                        "accessType", "API_KEY_LLM"));
        verify(subTaskExecutionService).executeCommand(command);
        verify(agentExecutionRecordService).markSuccess(44L);
    }

    @Test
    @DisplayName("should mark failed when consume throws")
    void shouldMarkFailedWhenConsumeThrowsException() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        when(subTaskService.getById(22L)).thenReturn(subTask);

        ExecutionCommand command = ExecutionCommand.builder()
                .recordId(44L)
                .eventId("evt-2")
                .subTaskId(22L)
                .agentId(11L)
                .trigger("assigned")
                .accessType(AgentAccessType.API_KEY_LLM)
                .build();

        when(agentExecutionRecordService.markRunning(44L)).thenReturn(true);
        when(agentExecutionRecordService.markFailed(44L, "exec failed")).thenReturn(true);
        doThrow(new BizException("exec failed")).when(subTaskExecutionService).executeCommand(command);

        // P1 修复后：consume 不再 rethrow，无需 try/catch
        localExecutionCommandConsumer.consume(command);



        verify(agentExecutionRecordService).markRunning(44L);
        verify(subTaskExecutionService).executeCommand(command);
        verify(agentExecutionRecordService).markFailed(44L, "exec failed");
    }

    @Test
    @DisplayName("should skip execution when markRunning returns false")
    void shouldSkipExecutionWhenMarkRunningReturnsFalse() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        when(subTaskService.getById(22L)).thenReturn(subTask);

        ExecutionCommand command = ExecutionCommand.builder()
                .recordId(44L)
                .eventId("evt-3")
                .subTaskId(22L)
                .agentId(11L)
                .trigger("assigned")
                .accessType(AgentAccessType.API_KEY_LLM)
                .build();

        when(agentExecutionRecordService.markRunning(44L)).thenReturn(false);

        localExecutionCommandConsumer.consume(command);

        verify(agentExecutionRecordService).markRunning(44L);
        verify(subTaskExecutionService, never()).executeCommand(command);
    }
}
