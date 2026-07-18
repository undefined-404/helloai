package com.helloai.core.task.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    @Mock
    private AgentSelector agentSelector;

    @Mock
    private AgentService agentService;

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

    // ══════════════════════════════════════════════════════════════
    //  N11 阈值回退：redispatchForFallback
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("N11: 找同角色 EXECUTOR 的 API_KEY_LLM Agent 并走 ResilientDispatcher")
    void shouldRedispatchForFallbackToApiKeyLlmAgent() {
        SubTask subTask = new SubTask();
        subTask.setId(41L);
        subTask.setTaskId(51L);

        Agent failedAgent = new Agent();
        failedAgent.setId(11L);
        failedAgent.setRole(AgentRole.EXECUTOR);

        Agent fallbackAgent = new Agent();
        fallbackAgent.setId(99L);
        fallbackAgent.setName("llm-executor");
        fallbackAgent.setRole(AgentRole.EXECUTOR);
        fallbackAgent.setAccessType(AgentAccessType.API_KEY_LLM);
        fallbackAgent.setStatus(AgentStatus.ACTIVE);
        fallbackAgent.setOnlineStatus(AgentOnlineStatus.ONLINE);
        fallbackAgent.setScore(100);

        when(subTaskService.resetToPendingForDispatch(
                41L, Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS,
                        SubTaskStatus.BLOCKED, SubTaskStatus.REWORK)))
                .thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(failedAgent);
        when(agentService.listActive()).thenReturn(List.of(fallbackAgent));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(41L, 11L, "consecutive_failure=5");

        assertThat(newAgentId).isEqualTo(99L);
        verify(taskTimelineService).recordEvent(
                51L,
                41L,
                "sub_task_dispatch_prepare",
                AgentRole.SYSTEM,
                99L,
                Map.of(
                        "trigger", "external_fallback",
                        "preferredAgentId", 99L,
                        "previousAgentId", 11L,
                        "reason", "consecutive_failure=5"));
        verify(resilientDispatcher).assignNext(99L, 41L);
    }

    @Test
    @DisplayName("N11: 失败 Agent 的 role 为 null 时回退到 EXECUTOR 选人")
    void shouldFallBackToExecutorWhenFailedAgentRoleNull() {
        SubTask subTask = new SubTask();
        subTask.setId(42L);
        subTask.setTaskId(52L);

        Agent failedAgent = new Agent();
        failedAgent.setId(11L);
        failedAgent.setRole(null);

        Agent fallbackAgent = new Agent();
        fallbackAgent.setId(99L);
        fallbackAgent.setRole(AgentRole.EXECUTOR);
        fallbackAgent.setAccessType(AgentAccessType.API_KEY_LLM);
        fallbackAgent.setStatus(AgentStatus.ACTIVE);
        fallbackAgent.setOnlineStatus(AgentOnlineStatus.ONLINE);
        fallbackAgent.setScore(100);

        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(failedAgent);
        when(agentService.listActive()).thenReturn(List.of(fallbackAgent));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(42L, 11L, "reason");

        assertThat(newAgentId).isEqualTo(99L);
        verify(resilientDispatcher).assignNext(99L, 42L);
    }

    @Test
    @DisplayName("N11: 没有 API_KEY_LLM 候选时抛 BizException，不调 ResilientDispatcher")
    void shouldThrowWhenNoApiKeyLlmCandidate() {
        SubTask subTask = new SubTask();
        subTask.setId(43L);
        subTask.setTaskId(53L);

        Agent failedAgent = new Agent();
        failedAgent.setId(11L);
        failedAgent.setRole(AgentRole.EXECUTOR);

        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(failedAgent);
        when(agentService.listActive()).thenReturn(List.of());  // 没有 LLM 候选

        assertThatThrownBy(() -> subTaskDispatchService.redispatchForFallback(43L, 11L, "reason"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("API_KEY_LLM");

        verify(resilientDispatcher, never())
                .assignNext(any(), any());
    }

    // ══════════════════════════════════════════════════════════════
    //  AgentHub V1 T1: redispatchAssignedTimeout 必须排除原 Agent
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ASSIGNED超时: pickAlternative 必须传 originalAgentId 以排除静默原 Agent")
    void shouldExcludeOriginalAgentWhenRedispatchingAssignedTimeout() {
        SubTask subTask = new SubTask();
        subTask.setId(61L);
        subTask.setTaskId(71L);

        Agent newAgent = new Agent();
        newAgent.setId(99L);
        newAgent.setName("replacement-agent");
        newAgent.setRole(AgentRole.EXECUTOR);

        when(subTaskService.resetToPendingForDispatch(61L, Set.of(SubTaskStatus.ASSIGNED)))
                .thenReturn(subTask);
        when(agentSelector.pickAlternative(11L, AgentRole.EXECUTOR)).thenReturn(newAgent);

        subTaskDispatchService.redispatchAssignedTimeout(61L, 11L, AgentRole.EXECUTOR);

        // 必须用 pickAlternative(originalAgentId, role)，而不是 pickPreferred(role)
        verify(agentSelector).pickAlternative(11L, AgentRole.EXECUTOR);
        verify(agentSelector, never()).pickPreferred(any());
        verify(resilientDispatcher).assignNext(99L, 61L);
    }

    @Test
    @DisplayName("ASSIGNED超时: 无可用替代 Agent 时不调 ResilientDispatcher")
    void shouldNotCallDispatcherWhenNoAlternativeAvailable() {
        SubTask subTask = new SubTask();
        subTask.setId(62L);
        subTask.setTaskId(72L);

        when(subTaskService.resetToPendingForDispatch(62L, Set.of(SubTaskStatus.ASSIGNED)))
                .thenReturn(subTask);
        // pickAlternative 返回 null（只有原 Agent 一个候选）
        when(agentSelector.pickAlternative(11L, AgentRole.EXECUTOR)).thenReturn(null);

        subTaskDispatchService.redispatchAssignedTimeout(62L, 11L, AgentRole.EXECUTOR);

        verify(agentSelector).pickAlternative(11L, AgentRole.EXECUTOR);
        verify(resilientDispatcher, never()).assignNext(any(), any());
    }
}
