package com.helloai.core.service;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.entity.SubTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.helloai.core.agent.dispatcher.ResilientDispatcher;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskDispatchService")
class SubTaskDispatchServiceTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private ResilientDispatcher resilientDispatcher;

    @Mock
    private TaskTimelineService taskTimelineService;

    @InjectMocks
    private SubTaskDispatchService subTaskDispatchService;

    @Test
    @DisplayName("BLOCKED 重分配走统一调度入口")
    void shouldDispatchBlockedSubTaskThroughResilientDispatcher() {
        SubTask subTask = new SubTask();
        subTask.setId(21L);
        subTask.setTaskId(31L);

        when(subTaskService.resetToPendingForDispatch(21L, Set.of(SubTaskStatus.BLOCKED)))
                .thenReturn(subTask);

        subTaskDispatchService.dispatchBlockedSubTask(21L, 11L);

        verify(taskTimelineService).recordEvent(
                31L,
                21L,
                "sub_task_dispatch_prepare",
                AgentRole.PLANNER,
                11L,
                Map.of("trigger", "blocked_reassign", "preferredAgentId", 11L));
        verify(resilientDispatcher).assignNext(11L, 21L);
    }

    @Test
    @DisplayName("离线任务重分配走弹性调度 fallback")
    void shouldRedispatchOfflineSubTaskThroughResilientDispatcher() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(32L);

        when(subTaskService.resetToPendingForDispatch(22L, Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS)))
                .thenReturn(subTask);

        subTaskDispatchService.redispatchOfflineSubTask(22L, 12L);

        verify(taskTimelineService).recordEvent(
                32L,
                22L,
                "sub_task_dispatch_prepare",
                AgentRole.SYSTEM,
                12L,
                Map.of("trigger", "agent_offline", "preferredAgentId", 12L, "previousAgentId", 12L));
        verify(resilientDispatcher).assignNext(12L, 22L);
    }
}
