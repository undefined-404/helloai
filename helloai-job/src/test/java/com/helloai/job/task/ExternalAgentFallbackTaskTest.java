package com.helloai.job.task;

import com.helloai.common.config.AgentFallbackProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.agent.service.ExternalAgentFailureTracker;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

/**
 * {@link ExternalAgentFallbackTask} 单元测试（N11 Phase 2C）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>enabled=false / 锁占用 / 无候选 → noop</li>
 *   <li>扫描到候选 → 写 audit + markFallbackTriggered + redispatchForFallback</li>
 *   <li>单 Agent 多个在跑子任务 → 逐个 redispatchForFallback，partial 失败不中断</li>
 *   <li>无在跑子任务 → 仍 markFallbackTriggered 写冷却</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExternalAgentFallbackTask")
class ExternalAgentFallbackTaskTest {

    @Mock
    private ExternalAgentFailureTracker failureTracker;
    @Mock
    private SubTaskDispatchService subTaskDispatchService;
    @Mock
    private SubTaskMapper subTaskMapper;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SubTaskService subTaskService;

    private AgentFallbackProperties properties;
    private ExternalAgentFallbackTask task;

    @BeforeEach
    void setUp() {
        properties = new AgentFallbackProperties();
        properties.setEnabled(true);
        properties.setFailureThreshold(3);
        properties.setCooldownMinutes(10);
        properties.setScanIntervalMs(60_000L);

        task = new ExternalAgentFallbackTask(
                failureTracker, subTaskDispatchService, subTaskMapper,
                taskTimelineService, properties, redis, subTaskService);

        // 默认让 tryLock 成功（用 lenient 避免 shouldSkipWhenDisabled 这种短路测试报 UnnecessaryStubbings）
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
    }

    @Nested
    @DisplayName("前置条件短路")
    class Precondition {

        @Test
        @DisplayName("enabled=false → 跳过（不查 DB）")
        void shouldSkipWhenDisabled() {
            properties.setEnabled(false);

            task.scan();

            verifyNoInteractions(failureTracker);
            verifyNoInteractions(subTaskDispatchService);
            verifyNoInteractions(subTaskMapper);
            verifyNoInteractions(taskTimelineService);
        }

        @Test
        @DisplayName("锁被占用 → 跳过")
        void shouldSkipWhenLocked() {
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false);

            task.scan();

            verify(failureTracker, never()).findFallbackCandidates();
        }

        @Test
        @DisplayName("无候选 Agent → 跳过；不解锁失败也不调用 markFallbackTriggered")
        void shouldSkipWhenNoCandidates() {
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of());

            task.scan();

            verify(failureTracker, times(1)).findFallbackCandidates();
            verify(subTaskDispatchService, never()).redispatchForFallback(anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("无候选 Agent → 仍要执行调度链遗留 PENDING 兜底（v2.6 §4.1）")
        void shouldStillRunPendingRecoveryWhenNoCandidates() {
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of());
            when(subTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord(anyInt()))
                    .thenReturn(List.of(5001L));
            SubTask latest = pendingUnassignedTask(5001L);
            when(subTaskService.getById(5001L)).thenReturn(latest);

            task.scan();

            verify(failureTracker, times(1)).findFallbackCandidates();
            verify(subTaskDispatchService, never()).redispatchForFallback(anyLong(), anyLong(), anyString());
            // 关键：即使无 N11 候选，也要执行 PENDING 兜底
            verify(subTaskMapper, times(1)).selectPendingUnassignedWithoutActiveExecutionRecord(anyInt());
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(5001L), eq(AgentRole.EXECUTOR));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  v2.6 §4.1：调度链遗留 PENDING 未指派兜底
    // ══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("调度链遗留 PENDING 未指派兜底（recoverPendingUnassigned）")
    class PendingUnassignedRecovery {

        @Test
        @DisplayName("扫描到候选 → 补读最新状态 → 仍为 PENDING 且未指派 → 自动选人")
        void shouldRecoverPendingUnassigned() {
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of());
            when(subTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord(anyInt()))
                    .thenReturn(List.of(5001L, 5002L));
            when(subTaskService.getById(5001L)).thenReturn(pendingUnassignedTask(5001L));
            when(subTaskService.getById(5002L)).thenReturn(pendingUnassignedTask(5002L));

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(5001L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(5002L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("扫描到的子任务状态已变化 → 跳过，不强制覆盖")
        void shouldSkipWhenStatusChanged() {
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of());
            when(subTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord(anyInt()))
                    .thenReturn(List.of(5001L));
            SubTask changed = pendingUnassignedTask(5001L);
            changed.setStatus(SubTaskStatus.ASSIGNED);  // 已被其他链路推进
            changed.setAssignedAgentId(999L);
            when(subTaskService.getById(5001L)).thenReturn(changed);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("扫描到的子任务不存在（已被删除） → 跳过")
        void shouldSkipWhenSubTaskDeleted() {
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of());
            when(subTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord(anyInt()))
                    .thenReturn(List.of(5001L));
            when(subTaskService.getById(5001L)).thenReturn(null);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("扫描到子任务已被指派 → 跳过")
        void shouldSkipWhenAlreadyAssigned() {
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of());
            when(subTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord(anyInt()))
                    .thenReturn(List.of(5001L));
            SubTask assigned = pendingUnassignedTask(5001L);
            assigned.setAssignedAgentId(777L);  // 已被分配
            when(subTaskService.getById(5001L)).thenReturn(assigned);

            task.scan();

            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("单条 dispatchPendingSubTaskAuto 失败 → 不中断其他候选")
        void shouldContinueOnSingleDispatchFailure() {
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of());
            when(subTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord(anyInt()))
                    .thenReturn(List.of(5001L, 5002L));
            when(subTaskService.getById(5001L)).thenReturn(pendingUnassignedTask(5001L));
            when(subTaskService.getById(5002L)).thenReturn(pendingUnassignedTask(5002L));
            when(subTaskDispatchService.dispatchPendingSubTaskAuto(eq(5001L), any()))
                    .thenThrow(new RuntimeException("synthetic failure"));

            task.scan();

            // 两条都被尝试
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(5001L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(5002L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("兜底不写入 N11 冷却 / external_fallback_count")
        void shouldNotPolluteN11Counters() {
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of());
            when(subTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord(anyInt()))
                    .thenReturn(List.of(5001L));
            when(subTaskService.getById(5001L)).thenReturn(pendingUnassignedTask(5001L));

            task.scan();

            // PENDING 兜底不写 N11 冷却
            verify(failureTracker, never()).markFallbackTriggered(anyLong());
            // PENDING 兜底不累加 external_fallback_count
            verify(subTaskMapper, never()).incrementExternalFallbackCount(anyLong(), any(OffsetDateTime.class));
        }
    }

    @Nested
    @DisplayName("候选处理")
    class CandidateProcessing {

        @Test
        @DisplayName("单候选 + 多在跑子任务 → 逐个 redispatch + markFallbackTriggered")
        void shouldRedispatchAllInFlightSubTasks() {
            Agent agent = cliAgent(101L, 5);
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of(agent));
            when(subTaskMapper.selectInFlightByAgent(eq(101L), anyInt()))
                    .thenReturn(List.of(subTask(11L, 101L), subTask(12L, 101L), subTask(13L, 101L)));

            task.scan();

            verify(taskTimelineService, times(1)).recordEvent(
                    any(), any(), eq("agent_external_fallback_triggered"),
                    any(), eq(101L), any());
            verify(subTaskDispatchService, times(3)).redispatchForFallback(anyLong(), eq(101L), anyString());
            verify(subTaskMapper, times(3)).incrementExternalFallbackCount(anyLong(), any(OffsetDateTime.class));
            verify(failureTracker, times(1)).markFallbackTriggered(101L);
        }

        @Test
        @DisplayName("单候选 + 无在跑子任务 → 仅 markFallbackTriggered 写冷却")
        void shouldMarkCooldownWhenNoInFlight() {
            Agent agent = cliAgent(102L, 5);
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of(agent));
            when(subTaskMapper.selectInFlightByAgent(eq(102L), anyInt())).thenReturn(List.of());

            task.scan();

            verify(subTaskDispatchService, never()).redispatchForFallback(anyLong(), anyLong(), anyString());
            verify(subTaskMapper, never()).incrementExternalFallbackCount(anyLong(), any());
            verify(failureTracker, times(1)).markFallbackTriggered(102L);
        }

        @Test
        @DisplayName("redispatchForFallback 单条失败不影响其他子任务")
        void shouldContinueOnSingleSubTaskFailure() {
            Agent agent = cliAgent(103L, 5);
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of(agent));
            when(subTaskMapper.selectInFlightByAgent(eq(103L), anyInt()))
                    .thenReturn(List.of(subTask(21L, 103L), subTask(22L, 103L), subTask(23L, 103L)));
            when(subTaskDispatchService.redispatchForFallback(eq(22L), eq(103L), anyString()))
                    .thenThrow(new RuntimeException("synthetic failure"));

            task.scan();

            // 三条子任务都被尝试（不会被单条异常中断）
            verify(subTaskDispatchService, times(3)).redispatchForFallback(anyLong(), eq(103L), anyString());
            // 仍要写冷却
            verify(failureTracker, times(1)).markFallbackTriggered(103L);
        }

        @Test
        @DisplayName("多候选 Agent → 各自走流程")
        void shouldProcessAllCandidates() {
            Agent a1 = cliAgent(201L, 5);
            Agent a2 = cliAgent(202L, 4);
            when(failureTracker.findFallbackCandidates()).thenReturn(List.of(a1, a2));
            when(subTaskMapper.selectInFlightByAgent(eq(201L), anyInt()))
                    .thenReturn(List.of(subTask(31L, 201L)));
            when(subTaskMapper.selectInFlightByAgent(eq(202L), anyInt()))
                    .thenReturn(List.of());

            task.scan();

            verify(taskTimelineService, times(1)).recordEvent(
                    any(), any(), eq("agent_external_fallback_triggered"),
                    any(), eq(201L), any());
            verify(failureTracker, times(1)).markFallbackTriggered(201L);
            verify(failureTracker, times(1)).markFallbackTriggered(202L);
            verify(subTaskDispatchService, times(1)).redispatchForFallback(eq(31L), eq(201L), anyString());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具
    // ═══════════════════════════════════════════════════════════════

    private static Agent cliAgent(Long id, int failureCount) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("cli-agent-" + id);
        a.setRole(AgentRole.EXECUTOR);
        a.setAccessType(AgentAccessType.CLI_CLIENT);
        a.setStatus(AgentStatus.ACTIVE);
        a.setOnlineStatus(AgentOnlineStatus.ONLINE);
        a.setConsecutiveFailureCount(failureCount);
        return a;
    }

    private static SubTask subTask(Long id, Long agentId) {
        SubTask s = new SubTask();
        s.setId(id);
        s.setAssignedAgentId(agentId);
        return s;
    }

    private static SubTask pendingUnassignedTask(Long id) {
        SubTask s = new SubTask();
        s.setId(id);
        s.setStatus(SubTaskStatus.PENDING);
        s.setAssignedAgentId(null);
        return s;
    }
}
