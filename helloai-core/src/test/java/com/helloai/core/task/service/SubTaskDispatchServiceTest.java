package com.helloai.core.task.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.agent.port.TaskDispatchPort;
import com.helloai.core.task.service.impl.SubTaskDispatchServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskDispatchService")
class SubTaskDispatchServiceTest {

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private TaskDispatchPort taskDispatchPort;

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

    @Mock
    private TaskService taskService;

    @InjectMocks
    private SubTaskDispatchServiceImpl subTaskDispatchService;

    @BeforeEach
    void setUp() {
        // 熔断默认禁用（现有测试不改动行为）
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
        verify(taskDispatchPort).assignNext(11L, 21L, null);
    }

    @Test
    @DisplayName("离线任务重分配走弹性调度 fallback")
    void shouldRedispatchOfflineSubTaskThroughResilientDispatcher() {
        SubTask subTask = new SubTask();
        subTask.setId(22L);
        subTask.setTaskId(32L);

        when(subTaskService.resetToPendingForDispatch(22L, Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS)))
                .thenReturn(subTask);
        // 依赖 ready 守卫 —— 默认依赖就绪才继续重派
        when(subTaskService.isReady(subTask)).thenReturn(true);

        subTaskDispatchService.redispatchOfflineSubTask(22L, 12L);

        // 专属可观测事件：离线改派（先于通用 dispatch_prepare 留痕）
        verify(taskTimelineService).recordEvent(
                32L,
                22L,
                "sub_task_offline_reassign",
                AgentRole.SYSTEM,
                12L,
                Map.of("previousAgentId", 12L));
        verify(taskTimelineService).recordEvent(
                32L,
                22L,
                "sub_task_dispatch_prepare",
                AgentRole.SYSTEM,
                12L,
                Map.of("trigger", "agent_offline", "preferredAgentId", 12L, "previousAgentId", 12L));
        verify(taskDispatchPort).assignNext(12L, 22L, null);
    }

    @Test
    @DisplayName("离线重分配时依赖未就绪 → 不重派保持 PENDING + 记 skip 事件")
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
        // 依赖未就绪未发生改派 → 不记离线改派专属事件
        verify(taskTimelineService, never()).recordEvent(
                anyLong(), anyLong(), eq("sub_task_offline_reassign"), any(), any(), any());
        verify(taskDispatchPort, never()).assignNext(anyLong(), anyLong(), any());
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

        AgentProfileSnapshot failedAgent = AgentProfileSnapshot.builder()
                .id(11L)
                .role(AgentRole.EXECUTOR)
                .build();

        AgentProfileSnapshot fallbackAgent = AgentProfileSnapshot.builder()
                .id(99L)
                .name("llm-executor")
                .role(AgentRole.EXECUTOR)
                .accessType(AgentAccessType.API_KEY_LLM)
                .status(AgentStatus.ACTIVE)
                .onlineStatus(AgentOnlineStatus.ONLINE)
                .score(100)
                .build();

        when(subTaskService.resetToPendingForDispatch(
                41L, Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS,
                        SubTaskStatus.BLOCKED, SubTaskStatus.REWORK)))
                .thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedAgent);
        when(agentService.listActiveProfiles()).thenReturn(List.of(fallbackAgent));

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
        verify(taskDispatchPort).assignNext(99L, 41L);
    }

    @Test
    @DisplayName("N11: 失败 Agent 的 role 为 null 时回退到 EXECUTOR 选人")
    void shouldFallBackToExecutorWhenFailedAgentRoleNull() {
        SubTask subTask = new SubTask();
        subTask.setId(42L);
        subTask.setTaskId(52L);

        AgentProfileSnapshot failedAgent = AgentProfileSnapshot.builder()
                .id(11L)
                .role(null)
                .build();

        AgentProfileSnapshot fallbackAgent = AgentProfileSnapshot.builder()
                .id(99L)
                .role(AgentRole.EXECUTOR)
                .accessType(AgentAccessType.API_KEY_LLM)
                .status(AgentStatus.ACTIVE)
                .onlineStatus(AgentOnlineStatus.ONLINE)
                .score(100)
                .build();

        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedAgent);
        when(agentService.listActiveProfiles()).thenReturn(List.of(fallbackAgent));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(42L, 11L, "reason");

        assertThat(newAgentId).isEqualTo(99L);
        verify(taskDispatchPort).assignNext(99L, 42L);
    }

    @Test
    @DisplayName("N11: 没有 API_KEY_LLM 候选时抛 BizException，不调 ResilientDispatcher")
    void shouldThrowWhenNoApiKeyLlmCandidate() {
        SubTask subTask = new SubTask();
        subTask.setId(43L);
        subTask.setTaskId(53L);

        AgentProfileSnapshot failedAgent = AgentProfileSnapshot.builder()
                .id(11L)
                .role(AgentRole.EXECUTOR)
                .build();

        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedAgent);
        when(agentService.listActiveProfiles()).thenReturn(List.of());  // 没有 LLM 候选

        assertThatThrownBy(() -> subTaskDispatchService.redispatchForFallback(43L, 11L, "reason"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("API_KEY_LLM");

        verify(taskDispatchPort, never())
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

        AgentProfileSnapshot failedAgent = AgentProfileSnapshot.builder()
                .id(11L)
                .role(AgentRole.EXECUTOR)
                .build();

        AgentProfileSnapshot fallbackAgent = AgentProfileSnapshot.builder()
                .id(99L)
                .role(AgentRole.EXECUTOR)
                .accessType(AgentAccessType.API_KEY_LLM)
                .status(AgentStatus.ACTIVE)
                .onlineStatus(AgentOnlineStatus.ONLINE)
                .score(100)
                .localExecutionCapable(false)
                .build();

        when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedAgent);
        when(agentService.listActiveProfiles()).thenReturn(List.of(fallbackAgent));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(44L, 11L, "consecutive_failure=5");

        assertThat(newAgentId).isNull();
        verify(taskTimelineService).recordEvent(
                54L, 44L, "sub_task_fallback_skip_need_human", AgentRole.SYSTEM, 99L,
                Map.of("reason", "execution_dense_no_local_capability",
                        "fallbackAgentId", 99L, "previousAgentId", 11L));
        verify(subTaskService).markManualIntervention(
                eq(44L), eq("fallback_skip_execution_dense"), anyMap());
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
    }

    @Test
    @DisplayName("§6.52: 执行密集任务但候选 supportsMCP=true → 正常回退")
    void shouldFallbackWhenExecutionDenseButAgentHasMcp() {
        SubTask subTask = new SubTask();
        subTask.setId(45L);
        subTask.setTaskId(55L);
        subTask.setContent("启动服务并执行 .ps1 脚本");

        AgentProfileSnapshot failedAgent = AgentProfileSnapshot.builder()
                .id(11L)
                .role(AgentRole.EXECUTOR)
                .build();

        AgentProfileSnapshot fallbackAgent = AgentProfileSnapshot.builder()
                .id(99L)
                .role(AgentRole.EXECUTOR)
                .accessType(AgentAccessType.API_KEY_LLM)
                .status(AgentStatus.ACTIVE)
                .onlineStatus(AgentOnlineStatus.ONLINE)
                .score(100)
                .localExecutionCapable(true)
                .build();

        when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedAgent);
        when(agentService.listActiveProfiles()).thenReturn(List.of(fallbackAgent));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(45L, 11L, "reason");

        assertThat(newAgentId).isEqualTo(99L);
        verify(subTaskService, never()).markManualIntervention(any(), any(), any());
        verify(taskDispatchPort).assignNext(99L, 45L);
    }

    @Test
    @DisplayName("§6.52: 非执行密集任务不受预检影响，正常回退")
    void shouldFallbackWhenNotExecutionDense() {
        SubTask subTask = new SubTask();
        subTask.setId(46L);
        subTask.setTaskId(56L);
        subTask.setContent("整理需求文档并输出分析结论");

        AgentProfileSnapshot failedAgent = AgentProfileSnapshot.builder()
                .id(11L)
                .role(AgentRole.EXECUTOR)
                .build();

        AgentProfileSnapshot fallbackAgent = AgentProfileSnapshot.builder()
                .id(99L)
                .role(AgentRole.EXECUTOR)
                .accessType(AgentAccessType.API_KEY_LLM)
                .status(AgentStatus.ACTIVE)
                .onlineStatus(AgentOnlineStatus.ONLINE)
                .score(100)
                .build();

        when(agentDispatchProperties.isFallbackSkipExecutionDense()).thenReturn(true);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedAgent);
        when(agentService.listActiveProfiles()).thenReturn(List.of(fallbackAgent));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(46L, 11L, "reason");

        assertThat(newAgentId).isEqualTo(99L);
        verify(subTaskService, never()).markManualIntervention(any(), any(), any());
        verify(taskDispatchPort).assignNext(99L, 46L);
    }

    // ══════════════════════════════════════════════════════════════
    //  AgentHub redispatchAssignedTimeout 必须排除原 Agent
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
        when(agentSelector.pickAlternative(11L, AgentRole.EXECUTOR, null)).thenReturn(newAgent);

        subTaskDispatchService.redispatchAssignedTimeout(61L, 11L, AgentRole.EXECUTOR);

        // 必须用 pickAlternative(originalAgentId, role)，而不是 pickPreferred(role)
        verify(agentSelector).pickAlternative(11L, AgentRole.EXECUTOR, null);
        verify(agentSelector, never()).pickPreferred(any(), any());
        verify(taskDispatchPort).assignNext(99L, 61L, null);
        // 关键调度节点：超时未领取改派事件留痕（用户可观测，独立于通用 dispatch_prepare）
        verify(taskTimelineService).recordEvent(
                71L, 61L, "sub_task_unclaimed_timeout_reassign", AgentRole.SYSTEM, 11L,
                Map.of("previousAgentId", 11L, "role", "EXECUTOR"));
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
        when(agentSelector.pickAlternative(11L, AgentRole.EXECUTOR, null)).thenReturn(null);

        subTaskDispatchService.redispatchAssignedTimeout(62L, 11L, AgentRole.EXECUTOR);

        verify(agentSelector).pickAlternative(11L, AgentRole.EXECUTOR, null);
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
        // 无候选时超时未领取的调度决策本身仍应留痕（便于后续死信排查）
        verify(taskTimelineService).recordEvent(
                72L, 62L, "sub_task_unclaimed_timeout_reassign", AgentRole.SYSTEM, 11L,
                Map.of("previousAgentId", 11L, "role", "EXECUTOR"));
    }

    // ══════════════════════════════════════════════════════════
    //  死信：熔断达阈值 → DEAD_LETTER；人工兜底 redispatchDeadLetter
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("dispatchPendingSubTaskAuto 达熔断阈值 → 置 DEAD_LETTER 且不再选人")
    void shouldMoveToDeadLetterWhenReassignAttemptsExceeded() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = new SubTask();
        subTask.setId(81L);
        subTask.setTaskId(91L);
        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setAttemptTotal(5);
        when(subTaskService.getById(81L)).thenReturn(subTask);
        // ready 守卫在熔断检查前，无依赖子任务视为就绪
        when(subTaskService.isReady(subTask)).thenReturn(true);

        Long result = subTaskDispatchService.dispatchPendingSubTaskAuto(81L, AgentRole.EXECUTOR);

        assertThat(result).isNull();
        verify(subTaskService).changeStatus(81L, SubTaskStatus.DEAD_LETTER, null,
                Map.of("dead_letter_reason", "reassign_attempt_exceeded",
                        "attempt_total", "5",
                        "max_reassign_attempts", "5"));
        verify(taskTimelineService).recordEvent(
                91L,
                81L,
                "sub_task_dead_letter",
                AgentRole.SYSTEM,
                null,
                Map.of("reason", "reassign_attempt_exceeded",
                        "attempt_total", 5,
                        "max_reassign_attempts", 5));
        verify(agentSelector, never()).pickPreferred(any(), any());
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
    }

    @Test
    @DisplayName("未达阈值 → 计数累加后正常进入调度链")
    void shouldIncrementCountAndDispatchWhenBelowThreshold() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = new SubTask();
        subTask.setId(82L);
        subTask.setTaskId(92L);
        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setAttemptTotal(2);
        when(subTaskService.getById(82L)).thenReturn(subTask);
        // ready 守卫在熔断检查前，无依赖子任务视为就绪
        when(subTaskService.isReady(subTask)).thenReturn(true);

        Agent preferred = new Agent();
        preferred.setId(99L);
        when(agentSelector.pickPreferred(AgentRole.EXECUTOR, null)).thenReturn(preferred);

        Long result = subTaskDispatchService.dispatchPendingSubTaskAuto(82L, AgentRole.EXECUTOR);

        assertThat(result).isEqualTo(99L);
        verify(subTaskMapper).incrementAttemptTotal(eq(82L), any(OffsetDateTime.class));
        verify(subTaskService, never()).changeStatus(anyLong(), any(), any(), any());
        verify(taskDispatchPort).assignNext(99L, 82L, null);
    }

    @Test
    @DisplayName("redispatchDeadLetter 清零计数并直接指派 ASSIGNED")
    void shouldResetCountAndAssignWhenRedispatchDeadLetter() {
        SubTask subTask = new SubTask();
        subTask.setId(83L);
        subTask.setTaskId(93L);
        subTask.setStatus(SubTaskStatus.DEAD_LETTER);
        when(subTaskService.getById(83L)).thenReturn(subTask);

        AgentProfileSnapshot agent = AgentProfileSnapshot.builder()
                .id(15L)
                .name("manual-agent")
                // LOG-20260903-011：死信重派目标必须 EXECUTOR 角色
                .role(AgentRole.EXECUTOR)
                .build();
        when(agentService.getProfileById(15L)).thenReturn(agent);

        subTaskDispatchService.redispatchDeadLetter(83L, 15L);

        verify(subTaskMapper).resetAttemptTotal(eq(83L), any(OffsetDateTime.class));
        verify(subTaskService).changeStatus(83L, SubTaskStatus.ASSIGNED, 15L);
        verify(taskTimelineService).recordEvent(
                93L,
                83L,
                "sub_task_dead_letter_manual_assign",
                AgentRole.SYSTEM,
                15L,
                Map.of("trigger", "manual_dead_letter_redispatch",
                        "assignedAgentId", 15L,
                        "agentName", "manual-agent",
                        "reworkCountReset", true));
    }

    @Test
    @DisplayName("非 DEAD_LETTER 状态调用 redispatchDeadLetter → BizException")
    void shouldThrowWhenRedispatchNonDeadLetter() {
        SubTask subTask = new SubTask();
        subTask.setId(84L);
        subTask.setTaskId(94L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        when(subTaskService.getById(84L)).thenReturn(subTask);

        assertThatThrownBy(() -> subTaskDispatchService.redispatchDeadLetter(84L, 15L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("DEAD_LETTER");

        verify(subTaskMapper, never()).resetAttemptTotal(anyLong(), any());
        verify(subTaskService, never()).changeStatus(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("死信重派目标非 EXECUTOR（PLANNER）→ BizException（LOG-20260903-011 契约）")
    void shouldRejectNonExecutorWhenRedispatchDeadLetter() {
        SubTask subTask = new SubTask();
        subTask.setId(85L);
        subTask.setTaskId(95L);
        subTask.setStatus(SubTaskStatus.DEAD_LETTER);
        when(subTaskService.getById(85L)).thenReturn(subTask);

        AgentProfileSnapshot planner = AgentProfileSnapshot.builder()
                .id(16L)
                .name("planner-agent")
                .role(AgentRole.PLANNER)
                .build();
        when(agentService.getProfileById(16L)).thenReturn(planner);

        assertThatThrownBy(() -> subTaskDispatchService.redispatchDeadLetter(85L, 16L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只支持执行者");
        verify(subTaskService, never()).changeStatus(anyLong(), any(), anyLong());
    }

    // ══════════════════════════════════════════════════════════════
    //  §6.58 P1：任务级回退策略约束（fallbackPolicy / difficulty）
    //  ══════════════════════════════════════════════════════════════

    private SubTask subTaskWithTaskId(long subTaskId, long taskId) {
        SubTask subTask = new SubTask();
        subTask.setId(subTaskId);
        subTask.setTaskId(taskId);
        return subTask;
    }

    private AgentProfileSnapshot failedExecutor(long id) {
        return AgentProfileSnapshot.builder()
                .id(id)
                .role(AgentRole.EXECUTOR)
                .build();
    }

    private AgentProfileSnapshot llmExecutor(long id) {
        return AgentProfileSnapshot.builder()
                .id(id)
                .role(AgentRole.EXECUTOR)
                .accessType(AgentAccessType.API_KEY_LLM)
                .status(AgentStatus.ACTIVE)
                .onlineStatus(AgentOnlineStatus.ONLINE)
                .score(100)
                .build();
    }

    @Test
    @DisplayName("fallbackPolicy=NONE → 跳过 N11 回退并标记人工介入，不落 LLM")
    void shouldSkipFallbackWhenPolicyNone() {
        SubTask subTask = subTaskWithTaskId(47L, 57L);
        Task task = new Task();
        task.setId(57L);
        task.setAgentPolicy(TaskAgentPolicy.build(null, null, null,
                TaskAgentPolicy.FallbackPolicy.NONE, null));
        when(taskService.getById(57L)).thenReturn(task);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedExecutor(11L));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(47L, 11L, "consecutive_failure=5");

        assertThat(newAgentId).isNull();
        verify(taskTimelineService).recordEvent(
                57L, 47L, "sub_task_fallback_skip_policy", AgentRole.SYSTEM, 11L,
                Map.of("reason", "fallback_policy_forbidden",
                        "fallbackPolicy", "NONE",
                        "difficulty", "MEDIUM",
                        "previousAgentId", 11L));
        verify(subTaskService).markManualIntervention(
                eq(47L), eq("fallback_skip_policy"), anyMap());
        verify(agentService, never()).listActiveProfiles();
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
    }

    @Test
    @DisplayName("difficulty=HIGH → 跳过 N11 回退并标记人工介入，不落 LLM")
    void shouldSkipFallbackWhenDifficultyHigh() {
        SubTask subTask = subTaskWithTaskId(48L, 58L);
        Task task = new Task();
        task.setId(58L);
        task.setAgentPolicy(TaskAgentPolicy.build(null, null, null,
                null, TaskAgentPolicy.Difficulty.HIGH));
        when(taskService.getById(58L)).thenReturn(task);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedExecutor(11L));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(48L, 11L, "consecutive_failure=5");

        assertThat(newAgentId).isNull();
        verify(subTaskService).markManualIntervention(
                eq(48L), eq("fallback_skip_policy"), anyMap());
        verify(agentService, never()).listActiveProfiles();
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
    }

    @Test
    @DisplayName("fallbackPolicy=RESTRICTED 且回退目标不在白名单 → 跳过并标记人工介入")
    void shouldSkipFallbackWhenRestrictedTargetOutsideWhitelist() {
        SubTask subTask = subTaskWithTaskId(49L, 59L);
        Task task = new Task();
        task.setId(59L);
        task.setAgentPolicy(TaskAgentPolicy.build(null, List.of(7L), null,
                TaskAgentPolicy.FallbackPolicy.RESTRICTED, null));
        when(taskService.getById(59L)).thenReturn(task);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedExecutor(11L));
        // 同角色 API_KEY_LLM 候选分数最高者为 99L，但不在白名单 [7L] 内
        when(agentService.listActiveProfiles()).thenReturn(List.of(llmExecutor(99L)));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(49L, 11L, "reason");

        assertThat(newAgentId).isNull();
        verify(taskTimelineService).recordEvent(
                59L, 49L, "sub_task_fallback_skip_policy", AgentRole.SYSTEM, 99L,
                Map.of("reason", "fallback_policy_restricted_not_in_whitelist",
                        "fallbackAgentId", 99L, "previousAgentId", 11L));
        verify(subTaskService).markManualIntervention(
                eq(49L), eq("fallback_skip_policy_restricted"), anyMap());
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
    }

    @Test
    @DisplayName("fallbackPolicy=RESTRICTED 且回退目标在白名单 → 正常回退")
    void shouldFallbackWhenRestrictedTargetInWhitelist() {
        SubTask subTask = subTaskWithTaskId(50L, 60L);
        Task task = new Task();
        task.setId(60L);
        task.setAgentPolicy(TaskAgentPolicy.build(null, List.of(99L), null,
                TaskAgentPolicy.FallbackPolicy.RESTRICTED, null));
        when(taskService.getById(60L)).thenReturn(task);
        when(subTaskService.resetToPendingForDispatch(any(), any())).thenReturn(subTask);
        when(agentService.getProfileById(11L)).thenReturn(failedExecutor(11L));
        when(agentService.listActiveProfiles()).thenReturn(List.of(llmExecutor(99L)));

        Long newAgentId = subTaskDispatchService.redispatchForFallback(50L, 11L, "reason");

        assertThat(newAgentId).isEqualTo(99L);
        verify(subTaskService, never()).markManualIntervention(any(), any(), any());
        verify(taskDispatchPort).assignNext(99L, 50L);
    }

    @Test
    @DisplayName("执行中卡死改派：先 block 标阻塞再走既有重调度链")
    void shouldRedispatchInProgressBlockThenReschedule() {
        SubTask subTask = subTaskWithTaskId(51L, 61L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        when(subTaskService.getById(51L)).thenReturn(subTask);
        when(subTaskService.resetToPendingForDispatch(51L, Set.of(SubTaskStatus.BLOCKED)))
                .thenReturn(subTask);

        subTaskDispatchService.redispatchInProgress(51L, 11L);

        // 第一步：人工阻塞（带原因，走 BLOCKED 收件箱通知 + timeline 审计）
        verify(subTaskService).block(51L, "人工判定执行停滞，改派新执行者", null);
        // 第二步：复用 dispatchBlockedSubTask 的既有调度链（timeline + assignNext）
        verify(taskTimelineService).recordEvent(
                61L, 51L, "sub_task_dispatch_prepare", AgentRole.PLANNER, 11L,
                Map.of("trigger", "blocked_reassign", "preferredAgentId", 11L));
        verify(taskDispatchPort).assignNext(11L, 51L, null);
    }

    @Test
    @DisplayName("人工换人：非 IN_PROGRESS/PAUSED 状态拒绝且不触发调度")
    void shouldRejectRedispatchInProgressWhenNotInProgressOrPaused() {
        SubTask subTask = subTaskWithTaskId(52L, 62L);
        subTask.setStatus(SubTaskStatus.PENDING);
        when(subTaskService.getById(52L)).thenReturn(subTask);

        assertThatThrownBy(() -> subTaskDispatchService.redispatchInProgress(52L, 11L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 IN_PROGRESS 或 PAUSED 状态的子任务才能改派");
        verify(subTaskService, never()).resume(anyLong());
        verify(subTaskService, never()).block(anyLong(), any(), any());
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
    }

    @Test
    @DisplayName("暂停后换人：先恢复 IN_PROGRESS 再 block 标阻塞再走既有重调度链")
    void shouldRedispatchPausedResumeThenBlockAndReschedule() {
        SubTask subTask = subTaskWithTaskId(53L, 63L);
        subTask.setStatus(SubTaskStatus.PAUSED);
        when(subTaskService.getById(53L)).thenReturn(subTask);
        when(subTaskService.resetToPendingForDispatch(53L, Set.of(SubTaskStatus.BLOCKED)))
                .thenReturn(subTask);

        subTaskDispatchService.redispatchInProgress(53L, 11L);

        // 顺序：先恢复执行权（PAUSED 到 IN_PROGRESS），再人工阻塞，最后走重调度链
        InOrder inOrder = inOrder(subTaskService);
        inOrder.verify(subTaskService).resume(53L);
        inOrder.verify(subTaskService).block(53L, "人工判定执行停滞，改派新执行者", null);
        verify(taskDispatchPort).assignNext(11L, 53L, null);
    }

    // ══════════════════════════════════════════════════════════════
    //  804 修复：退避时钟改绑 last_attempt_time + 闸门拦截可观测 + 换人不被自身时钟拦截
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("退避时钟改绑 last_attempt_time：update_time 新鲜但从未累加 → 放行（不再被 update_time 误拦）")
    void shouldNotBlockWhenUpdateTimeFreshButLastAttemptTimeNull() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = subTaskWithTaskId(71L, 81L);
        subTask.setStatus(SubTaskStatus.BLOCKED);
        subTask.setAttemptTotal(3);
        // block()/changeStatus 会刷新 update_time；若闸门仍读 update_time 会被拦截
        subTask.setUpdateTime(OffsetDateTime.now());
        // 退避时钟为空（从未真正 incrementAttemptTotal）→ 不应拦截
        subTask.setLastAttemptTime(null);
        when(subTaskService.getById(71L)).thenReturn(subTask);
        when(subTaskService.resetToPendingForDispatch(71L, Set.of(SubTaskStatus.BLOCKED)))
                .thenReturn(subTask);

        var result = subTaskDispatchService.dispatchBlockedSubTask(71L, 11L);

        assertThat(result.applied()).isTrue();
        verify(taskDispatchPort).assignNext(11L, 71L, null);
    }

    @Test
    @DisplayName("退避窗口内（last_attempt_time 新鲜）→ 拦截 + 落 sub_task_redispatch_skipped（不再静默）")
    void shouldBlockAndRecordSkipWhenWithinBackoffByLastAttemptTime() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = subTaskWithTaskId(72L, 82L);
        subTask.setStatus(SubTaskStatus.BLOCKED);
        subTask.setAttemptTotal(1);                       // 第 1 档退避 60s
        subTask.setLastAttemptTime(OffsetDateTime.now()); // 刚累加 → 窗口内
        when(subTaskService.getById(72L)).thenReturn(subTask);

        var result = subTaskDispatchService.dispatchBlockedSubTask(72L, 11L);

        assertThat(result.applied()).isFalse();
        assertThat(result.reason()).isEqualTo("backoff");
        assertThat(result.nextAllowed()).isNotNull();
        // 退避不消耗预算、不改状态、不派发
        verify(subTaskMapper, never()).incrementAttemptTotal(anyLong(), any());
        verify(subTaskService, never()).resetToPendingForDispatch(anyLong(), any());
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
        // 可观测性：落 sub_task_redispatch_skipped（reason=backoff）
        verify(taskTimelineService).recordEvent(
                eq(82L), eq(72L), eq("sub_task_redispatch_skipped"), eq(AgentRole.SYSTEM), isNull(), anyMap());
    }

    @Test
    @DisplayName("换人：退避时钟为空时不被自身刷新的 update_time 拦截（804 主因回归锁）")
    void shouldRedispatchInProgressWithoutBeingBlockedBySelfRefreshedClock() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = subTaskWithTaskId(73L, 83L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        subTask.setAttemptTotal(3);
        subTask.setUpdateTime(OffsetDateTime.now()); // block() 前置判定：这里若仍读 update_time 必然拦截
        subTask.setLastAttemptTime(null);            // 退避时钟为空 → 放行
        when(subTaskService.getById(73L)).thenReturn(subTask);
        when(subTaskService.resetToPendingForDispatch(73L, Set.of(SubTaskStatus.BLOCKED)))
                .thenReturn(subTask);

        var result = subTaskDispatchService.redispatchInProgress(73L, 11L);

        assertThat(result.applied()).isTrue();
        verify(subTaskService).block(73L, "人工判定执行停滞，改派新执行者", null);
        verify(taskDispatchPort).assignNext(11L, 73L, null);
    }

    @Test
    @DisplayName("换人：确在退避窗口内 → 不改动状态（不 block）+ 返回 skipped + 落 skip 时间线")
    void shouldRedispatchInProgressSkipWithoutBlockingWhenWithinBackoff() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = subTaskWithTaskId(74L, 84L);
        subTask.setStatus(SubTaskStatus.IN_PROGRESS);
        subTask.setAttemptTotal(2);                       // 第 2 档 180s
        subTask.setLastAttemptTime(OffsetDateTime.now()); // 窗口内
        when(subTaskService.getById(74L)).thenReturn(subTask);

        var result = subTaskDispatchService.redispatchInProgress(74L, 11L);

        assertThat(result.applied()).isFalse();
        assertThat(result.reason()).isEqualTo("backoff");
        // 关键：闸门判定在 block() 之前 → 被拦时不 block、不 resume、不派发
        verify(subTaskService, never()).block(anyLong(), any(), any());
        verify(subTaskService, never()).resume(anyLong());
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
        verify(taskTimelineService).recordEvent(
                eq(84L), eq(74L), eq("sub_task_redispatch_skipped"), eq(AgentRole.SYSTEM), isNull(), anyMap());
    }

    @Test
    @DisplayName("预算耗尽（熔断转死信）→ 拦截 + 落 sub_task_redispatch_skipped（reason=circuit_open）")
    void shouldEscalateToDeadLetterAndRecordSkipWhenBudgetExhausted() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = subTaskWithTaskId(75L, 85L);
        subTask.setStatus(SubTaskStatus.BLOCKED);
        subTask.setAttemptTotal(5);
        when(subTaskService.getById(75L)).thenReturn(subTask);

        var result = subTaskDispatchService.dispatchBlockedSubTask(75L, 11L);

        assertThat(result.applied()).isFalse();
        assertThat(result.reason()).isEqualTo("circuit_open");
        verify(subTaskService).changeStatus(eq(75L), eq(SubTaskStatus.DEAD_LETTER), isNull(), anyMap());
        verify(taskTimelineService).recordEvent(
                eq(85L), eq(75L), eq("sub_task_redispatch_skipped"), eq(AgentRole.SYSTEM), isNull(), anyMap());
    }

    @Test
    @DisplayName("系统入口（超时回收）走熔断 → 仍落 sub_task_redispatch_skipped（reason=circuit_open，一次性）")
    void shouldRecordSkipOnCircuitOpenFromSystemEntry() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = subTaskWithTaskId(76L, 86L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        subTask.setAttemptTotal(5);
        when(subTaskService.getById(76L)).thenReturn(subTask);

        subTaskDispatchService.redispatchAssignedTimeout(76L, 11L, AgentRole.EXECUTOR);

        verify(subTaskService).changeStatus(eq(76L), eq(SubTaskStatus.DEAD_LETTER), isNull(), anyMap());
        verify(taskTimelineService).recordEvent(
                eq(86L), eq(76L), eq("sub_task_redispatch_skipped"), eq(AgentRole.SYSTEM), isNull(), anyMap());
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
    }

    @Test
    @DisplayName("系统入口（超时回收）退避窗口内 → 不落 skip 事件（周期巡检防刷屏）")
    void shouldNotRecordSkipOnBackoffFromSystemEntry() {
        when(agentDispatchProperties.getMaxReassignAttempts()).thenReturn(5);

        SubTask subTask = subTaskWithTaskId(77L, 87L);
        subTask.setStatus(SubTaskStatus.ASSIGNED);
        subTask.setAttemptTotal(1);
        subTask.setLastAttemptTime(OffsetDateTime.now());
        when(subTaskService.getById(77L)).thenReturn(subTask);

        subTaskDispatchService.redispatchAssignedTimeout(77L, 11L, AgentRole.EXECUTOR);

        // 退避窗口内：系统入口只静默跳过，不落 skip 事件、不派发、不改状态
        verify(taskTimelineService, never()).recordEvent(
                anyLong(), anyLong(), eq("sub_task_redispatch_skipped"), any(), any(), anyMap());
        verify(subTaskService, never()).resetToPendingForDispatch(anyLong(), any());
        verify(taskDispatchPort, never()).assignNext(any(), any(), any());
    }
}
