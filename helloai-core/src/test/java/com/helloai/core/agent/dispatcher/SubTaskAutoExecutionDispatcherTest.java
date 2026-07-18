package com.helloai.core.agent.dispatcher;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.SubTaskAssignedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.command.ExecutionCommandService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskAutoExecutionDispatcher")
class SubTaskAutoExecutionDispatcherTest {

    @Mock
    private AgentService agentService;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private ExecutionCommandService executionCommandService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @InjectMocks
    private SubTaskAutoExecutionDispatcher dispatcher;

    @Test
    @DisplayName("API_KEY_LLM 在 ASSIGNED 后创建执行命令")
    void shouldCreateExecutionCommandWhenAssignedAgentIsApiKeyLlm() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(33L);

        when(agentService.getById(11L)).thenReturn(agent);
        when(subTaskService.getById(22L)).thenReturn(subTask);

        dispatcher.onAssigned(new SubTaskAssignedEvent(22L, 11L));

        verify(taskTimelineService).recordEvent(
                33L, 22L, "sub_task_auto_execute_dispatch", AgentRole.SYSTEM, 11L,
                java.util.Map.of("trigger", "assigned", "accessType", "API_KEY_LLM"));
        verify(executionCommandService).createAssignedCommand(22L, 11L, "assigned");
    }

    @Test
    @DisplayName("CLI_CLIENT 在 ASSIGNED 后跳过自动执行")
    void shouldSkipWhenAssignedAgentIsCliClient() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setAccessType(AgentAccessType.CLI_CLIENT);

        when(agentService.getById(11L)).thenReturn(agent);

        dispatcher.onAssigned(new SubTaskAssignedEvent(22L, 11L));

        verify(subTaskService, never()).getById(22L);
        verify(taskTimelineService, never()).recordEvent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
        verify(executionCommandService, never()).createAssignedCommand(22L, 11L, "assigned");
    }
}
