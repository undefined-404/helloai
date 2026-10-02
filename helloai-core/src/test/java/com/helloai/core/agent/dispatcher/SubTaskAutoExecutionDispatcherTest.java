package com.helloai.core.agent.dispatcher;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.shared.event.SubTaskAssignedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.core.agent.service.AgentService;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskAutoExecutionDispatcher")
class SubTaskAutoExecutionDispatcherTest {

    @Mock
    private AgentService agentService;

    @Mock
    private SubTaskQueryPort subTaskQueryPort;

    @Mock
    private ExecutionCommandService executionCommandService;

    @Mock
    private TaskTimelinePort taskTimelinePort;

    @InjectMocks
    private SubTaskAutoExecutionDispatcher dispatcher;

    @Test
    @DisplayName("API_KEY_LLM 在 ASSIGNED 后创建执行命令")
    void shouldCreateExecutionCommandWhenAssignedAgentIsApiKeyLlm() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);

        SubTaskSnapshot subTask = SubTaskSnapshot.builder()
                .id(22L)
                .taskId(33L)
                .build();

        when(agentService.getById(11L)).thenReturn(agent);
        when(subTaskQueryPort.findById(22L)).thenReturn(subTask);

        dispatcher.onAssigned(new SubTaskAssignedEvent(22L, 11L));

        verify(taskTimelinePort).recordEvent(
                33L, 22L, "sub_task_auto_execute_dispatch", AgentRole.SYSTEM, 11L,
                java.util.Map.of("trigger", "assigned", "accessType", "API_KEY_LLM"));
        verify(executionCommandService).createAssignedCommand(22L, 11L, "assigned", List.of());
    }

    @Test
    @DisplayName("CLI_CLIENT 在 ASSIGNED 后跳过自动执行")
    void shouldSkipWhenAssignedAgentIsCliClient() {
        Agent agent = new Agent();
        agent.setId(11L);
        agent.setAccessType(AgentAccessType.CLI_CLIENT);

        when(agentService.getById(11L)).thenReturn(agent);

        dispatcher.onAssigned(new SubTaskAssignedEvent(22L, 11L));

        verify(subTaskQueryPort, never()).findById(22L);
        verify(taskTimelinePort, never()).recordEvent(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
        verify(executionCommandService, never()).createAssignedCommand(22L, 11L, "assigned", List.of());
    }
}
