package com.helloai.core.task.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private SubTaskMapper subTaskMapper;

    @Mock
    private AgentDispatchProperties agentDispatchProperties;

    @InjectMocks
    private SubTaskDispatchService subTaskDispatchService;

    @BeforeEach
    void setUp() {
        // V24 熔断默认禁用（现有测试不改动行为）
        lenient().when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(0);
    }

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
        // V27.1: 依赖 ready 守卫 —— 默认依赖就绪才继续重派
        when(subTaskService.isReady(subTask)).thenReturn(true);

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

    @Test
    @DisplayName("V27.1: 离线重分配时依赖未就绪 → 不重派保持 PENDING + 记 skip 事件")
    void shouldSkipOfflineRedispatchWhenDependencyNotReady() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(32L);

        when(subTaskService.resetToPendingForDispatch(22L, Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS)))
                .thenReturn(subTask);
        when(subTaskService.isReady(subTask)).thenReturn(false);

        subTaskDispatchService.redispatchOfflineSubTask(22L, 12L);

        verify(taskTimelineService).recordEvent(
                32L,
                22L,
                "sub_task_dispatch_skip_dependency",
                AgentRole.SYSTEM,
                12L,
                Map.of("trigger", "agent_offline", "reason", "dependency_not_ready",
                        "dependsOn", subTask.dependsOnIdList()));
        verify(resilientDispatcher, never()).assignNext(anyLong(), anyLong());
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
    //  §6.52 人工介入：执行密集任务不自动回退给无本机能力的 API_KEY_LLM
    //  ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("§6.52: 执行密集任务 + 候选无本机能力 → 跳过回退并标记人工介入")
    void shouldSkipFallbackWhenExecutionDenseAndNoLocalCapability() {
        SubTask subTask = new SubTask();
        subTask.setId(44L);
        subTask.setTaskId(54L);
        subTask.setContent("实现 verify-order-expire.ps1 超时取消校验脚本，需启动服务执行");

        Agent failedAgent = new Agent();
        failedAgent.setId(11L);
        failedAgent.setRole(AgentRole.EXECUTOR);

        Agent fallbackAgent = new Agent();
        fallbackAgent.setId(99L);
        fallbackAgent.setRole(AgentRole.EXECUTOR);
        fallbackAgent.setAccessType(AgentAccessType.API_KEY_LLM);
        fallbackAgent.setStatus(AgentStatus.ACTIVE);
        fallbackAgent.setOnlineStatus(AgentOnlineStatus.ONLINE);
        fallbackAgent.setScore(100);
        fallbackAgent.setCapabilities(Map.of("supportsMCP", false));

        when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(failedAgent);
        when(agentService.listActive()).thenReturn(List.of(fallbackAgent));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(44L, 11L, "consecutive_failure=5");

        assertThat(newAgentId).isNull();
        verify(taskTimelineService).recordEvent(
                54L, 44L, "sub_task_fallback_skip_need_human", AgentRole.SYSTEM, 99L,
                Map.of("reason", "execution_dense_no_local_capability",
                        "fallbackAgentId", 99L, "previousAgentId", 11L));
        verify(subTaskService).markManualIntervention(
                eq(44L), eq("fallback_skip_execution_dense"), anyMap());
        verify(resilientDispatcher, never()).assignNext(any(), any());
    }

    @Test
    @DisplayName("§6.52: 执行密集任务但候选 supportsMCP=true → 正常回退")
    void shouldFallbackWhenExecutionDenseButAgentHasMcp() {
        SubTask subTask = new SubTask();
        subTask.setId(45L);
        subTask.setTaskId(55L);
        subTask.setContent("启动服务并执行 .ps1 脚本");

        Agent failedAgent = new Agent();
        failedAgent.setId(11L);
        failedAgent.setRole(AgentRole.EXECUTOR);

        Agent fallbackAgent = new Agent();
        fallbackAgent.setId(99L);
        fallbackAgent.setRole(AgentRole.EXECUTOR);
        fallbackAgent.setAccessType(AgentAccessType.API_KEY_LLM);
        fallbackAgent.setStatus(AgentStatus.ACTIVE);
        fallbackAgent.setOnlineStatus(AgentOnlineStatus.ONLINE);
        fallbackAgent.setScore(100);
        fallbackAgent.setCapabilities(Map.of("supportsMCP", true));

        when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(failedAgent);
        when(agentService.listActive()).thenReturn(List.of(fallbackAgent));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(45L, 11L, "reason");

        assertThat(newAgentId).isEqualTo(99L);
        verify(subTaskService, never()).markManualIntervention(any(), any(), any());
        verify(resilientDispatcher).assignNext(99L, 45L);
    }

    @Test
    @DisplayName("§6.52: 非执行密集任务不受预检影响，正常回退")
    void shouldFallbackWhenNotExecutionDense() {
        SubTask subTask = new SubTask();
        subTask.setId(46L);
        subTask.setTaskId(56L);
        subTask.setContent("整理需求文档并输出分析结论");

        Agent failedAgent = new Agent();
        failedAgent.setId(11L);
        failedAgent.setRole(AgentRole.EXECUTOR);

        Agent fallbackAgent = new Agent();
        fallbackAgent.setId(99L);
        fallbackAgent.setRole(AgentRole.EXECUTOR);
        fallbackAgent.setAccessType(AgentAccessType.API_KEY_LLM);
        fallbackAgent.setStatus(AgentStatus.ACTIVE);
        fallbackAgent.setOnlineStatus(AgentOnlineStatus.ONLINE);
        fallbackAgent.setScore(100);

        when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getById(11L)).thenReturn(failedAgent);
        when(agentService.listActive()).thenReturn(List.of(fallbackAgent));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(46L, 11L, "reason");

        assertThat(newAgentId).isEqualTo(99L);
        verify(subTaskService, never()).markManualIntervention(any(), any(), any());
        verify(resilientDispatcher).assignNext(99L, 46L);
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

    // ══════════════════════════════════════════════════════════
    //  V25 死信：熔断达阈值 → DEAD_LETTER；人工兜底 redispatchDeadLetter
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("V25: dispatchPendingSubTaskAuto 达熔断阈值 → 置 DEAD_LETTER 且不再选人")
    void shouldMoveToDeadLetterWhenReassignAttemptsExceeded() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = new SubTask();
        subTask.setId(81L);
        subTask.setTaskId(91L);
        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setReassignAttemptCount(5);
        when(subTaskService.getById(81L)).thenReturn(subTask);
        // V27 ready 守卫在熔断检查前，无依赖子任务视为就绪
        when(subTaskService.isReady(subTask)).thenReturn(true);

        Long result = subTaskDispatchService.dispatchPendingSubTaskAuto(81L, AgentRole.EXECUTOR);

        assertThat(result).isNull();
        verify(subTaskService).changeStatus(81L, SubTaskStatus.DEAD_LETTER, null,
                Map.of("dead_letter_reason", "reassign_attempt_exceeded",
                        "reassign_attempt_count", "5",
                        "max_reassign_attempts", "5"));
        verify(taskTimelineService).recordEvent(
                91L,
                81L,
                "sub_task_dead_letter",
                AgentRole.SYSTEM,
                null,
                Map.of("reason", "reassign_attempt_exceeded",
                        "reassign_attempt_count", 5,
                        "max_reassign_attempts", 5));
        verify(agentSelector, never()).pickPreferred(any());
        verify(resilientDispatcher, never()).assignNext(any(), any());
    }

    @Test
    @DisplayName("V25: 未达阈值 → 计数累加后正常进入调度链")
    void shouldIncrementCountAndDispatchWhenBelowThreshold() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = new SubTask();
        subTask.setId(82L);
        subTask.setTaskId(92L);
        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setReassignAttemptCount(2);
        when(subTaskService.getById(82L)).thenReturn(subTask);
        // V27 ready 守卫在熔断检查前，无依赖子任务视为就绪
        when(subTaskService.isReady(subTask)).thenReturn(true);

        Agent preferred = new Agent();
        preferred.setId(99L);
        when(agentSelector.pickPreferred(AgentRole.EXECUTOR)).thenReturn(preferred);

        Long result = subTaskDispatchService.dispatchPendingSubTaskAuto(82L, AgentRole.EXECUTOR);

        assertThat(result).isEqualTo(99L);
        verify(subTaskMapper).incrementReassignAttemptCount(eq(82L), any(OffsetDateTime.class));
        verify(subTaskService, never()).changeStatus(anyLong(), any(), any(), any());
        verify(resilientDispatcher).assignNext(99L, 82L);
    }

    @Test
    @DisplayName("V25: redispatchDeadLetter 清零计数并直接指派 ASSIGNED")
    void shouldResetCountAndAssignWhenRedispatchDeadLetter() {
        SubTask subTask = new SubTask();
        subTask.setId(83L);
        subTask.setTaskId(93L);
        subTask.setStatus(SubTaskStatus.DEAD_LETTER);
        when(subTaskService.getById(83L)).thenReturn(subTask);

        Agent agent = new Agent();
        agent.setId(15L);
        agent.setName("manual-agent");
        when(agentService.getById(15L)).thenReturn(agent);

        subTaskDispatchService.redispatchDeadLetter(83L, 15L);

        verify(subTaskMapper).resetReassignAttemptCount(eq(83L), any(OffsetDateTime.class));
        verify(subTaskService).changeStatus(83L, SubTaskStatus.ASSIGNED, 15L);
        verify(taskTimelineService).recordEvent(
                93L,
                83L,
                "sub_task_dead_letter_manual_assign",
                AgentRole.SYSTEM,
                15L,
                Map.of("trigger", "manual_dead_letter_redispatch",
                        "assignedAgentId", 15L,
                        "agentName", "manual-agent"));
    }

    @Test
    @DisplayName("V25: 非 DEAD_LETTER 状态调用 redispatchDeadLetter → BizException")
    void shouldThrowWhenRedispatchNonDeadLetter() {
        SubTask subTask = new SubTask();
        subTask.setId(84L);
        subTask.setTaskId(94L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        when(subTaskService.getById(84L)).thenReturn(subTask);

        assertThatThrownBy(() -> subTaskDispatchService.redispatchDeadLetter(84L, 15L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("DEAD_LETTER");

        verify(subTaskMapper, never()).resetReassignAttemptCount(anyLong(), any());
        verify(subTaskService, never()).changeStatus(anyLong(), any(), anyLong());
    }
}
