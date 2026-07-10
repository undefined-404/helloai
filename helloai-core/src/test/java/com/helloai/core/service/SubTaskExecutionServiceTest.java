package com.helloai.core.service;

import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskExecutionService")
class SubTaskExecutionServiceTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private AgentService agentService;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private ExecutionResultHandler executionResultHandler;

    @InjectMocks
    private SubTaskExecutionService subTaskExecutionService;

    @Test
    @DisplayName("should propagate exception when executeSync throws")
    void shouldPropagateOriginalExceptionWhenExecuteSyncThrows() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);
        subTask.setAssignedAgent(44L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        subTask.setTitle("demo");
        subTask.setContent("demo content");

        Agent agent = new Agent();
        agent.setId(44L);

        RuntimeException root = new RuntimeException();
        when(subTaskService.getById(22L)).thenReturn(subTask);
        when(agentService.getById(44L)).thenReturn(agent);
        when(platformAgentExecutionService.executeSync(org.mockito.ArgumentMatchers.same(agent), any()))
                .thenThrow(root);

        ExecutionCommand command = ExecutionCommand.builder()
                .subTaskId(22L)
                .agentId(44L)
                .trigger("test")
                .build();

        assertThatThrownBy(() -> subTaskExecutionService.executeCommand(command))
                .isSameAs(root);

        verify(subTaskService).start(22L);
        verify(executionResultHandler).handleFailure(22L, 44L, root);
    }

    @Test
    @DisplayName("should throw BizException when agentId mismatch")
    void shouldThrowWhenAgentIdMismatch() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setAssignedAgent(44L);

        when(subTaskService.getById(22L)).thenReturn(subTask);

        ExecutionCommand command = ExecutionCommand.builder()
                .subTaskId(22L)
                .agentId(99L)
                .trigger("test")
                .build();

        assertThatThrownBy(() -> subTaskExecutionService.executeCommand(command))
                .isInstanceOf(com.helloai.common.base.BizException.class);
    }

    @Test
    @DisplayName("should throw BizException when agentId is null")
    void shouldThrowWhenCommandAgentIdIsNull() {
        ExecutionCommand command = ExecutionCommand.builder()
                .subTaskId(22L)
                .agentId(null)
                .trigger("test")
                .build();

        assertThatThrownBy(() -> subTaskExecutionService.executeCommand(command))
                .isInstanceOf(com.helloai.common.base.BizException.class)
                .hasMessageContaining("agentId");
    }
}
