package com.helloai.job.task;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.common.config.AgentHealthProperties;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentHealthCheckTask} 单元测试（§4.1 二次选人加固）。
 *
 * <p>§7.1 去 Mapper 直连后：巡检读写一律经 {@link AgentService} / {@link SubTaskService}
 * 出口，测试相应 mock Service（原 Mapper mock 已移除）。</p>
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>首选重派（弹性 fallback）成功 → 不触发二次自动选人</li>
 *   <li>首选失败 → 二次自动选人（dispatchPendingSubTaskAuto）成功</li>
 *   <li>首选失败 + 二次也失败 → 计入 failed，两条异常都记录</li>
 *   <li>CAS 标 OFFLINE 返回 0 → 不触发重派、不写 timeline、不调用 recordFailure</li>
 *   <li>Redis TTL 仍在 → 跳过 OFFLINE 流程</li>
 *   <li>offlineMinutes 配置 ≤ 0 → 禁用扫描</li>
 *   <li>G-015 B1：持 ACTIVE 租约 → 跳过离线处置；持在飞子任务 → 用加长 cutoff；
 *       租约/在飞查询异常 → fail-close 不放宽</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentHealthCheckTask")
class AgentHealthCheckTaskTest {

    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private AgentService agentService;
    @Mock
    private SubTaskDispatchService subTaskDispatchService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ExternalAgentFailureTracker failureTracker;
    @Mock
    private AgentDutyLeaseService agentDutyLeaseService;
    @Mock
    private SubTaskService subTaskService;

    private AgentHealthProperties healthProperties;
    private AgentHealthCheckTask task;

    @BeforeEach
    void setUp() {
        healthProperties = new AgentHealthProperties();
        healthProperties.setOfflineMinutes(5);
        healthProperties.setInFlightGraceMinutes(30);

        task = new AgentHealthCheckTask(
                taskTimelineService,
                agentService, subTaskDispatchService, redis,
                failureTracker, healthProperties, agentDutyLeaseService,
                subTaskService);

        // v1.2 §阶段2：SETNX 手写锁迁 ShedLock（代理拦截，单测直建对象无锁分支）；
        // StringRedisTemplate 保留用于 isRedisAlive 心跳 TTL 二次验证
        lenient().when(redis.hasKey(anyString())).thenReturn(false);
    }

    /**
     * 通过反射调用 private 方法 reassignStaleTasks(Agent)，覆盖二次选人加固逻辑。
     * §4.1：reassignStaleTasks 已重构为按 Agent 维度调用，无需提前查找字段。
     */
    private int invokeReassignStaleTasks(Agent staleAgent) throws Exception {
        java.lang.reflect.Method m = AgentHealthCheckTask.class.getDeclaredMethod(
                "reassignStaleTasks", Agent.class);
        m.setAccessible(true);
        try {
            m.invoke(task, staleAgent);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            if (ite.getCause() instanceof Exception) {
                throw (Exception) ite.getCause();
            }
            throw ite;
        }
        return 0;
    }

    @Nested
    @DisplayName("checkHealth 前置条件")
    class Precondition {

        @Test
        @DisplayName("offlineMinutes <= 0 → 禁用扫描")
        void shouldDisableScanWhenThresholdZero() {
            healthProperties.setOfflineMinutes(0);

            task.checkHealth();

            verify(agentService, never()).listStaleSince(any());
        }

        @Test
        @DisplayName("无超时 Agent → 直接返回")
        void shouldReturnWhenNoStaleAgent() {
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of());

            task.checkHealth();

            verify(agentService, times(1)).listStaleSince(any(OffsetDateTime.class));
            verify(subTaskService, never()).listInFlightAssignedOrInProgress(anyLong());
        }
    }

    @Nested
    @DisplayName("reassignStaleTasks 二次选人")
    class ReassignStaleTasks {

        @Test
        @DisplayName("首选 redispatchOfflineSubTask 成功 → 不触发二次自动选人")
        void shouldOnlyUseFallbackWhenPrimarySucceeds() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            SubTask t1 = assignedSubTask(11L, 101L);
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong())).thenReturn(List.of(t1));

            invokeReassignStaleTasks(stale);

            verify(subTaskDispatchService, times(1))
                    .redispatchOfflineSubTask(eq(11L), eq(101L));
            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("首选失败 → 二次走补偿重载（不重复计数）用 stale.role 重新选人")
        void shouldUseStaleRoleWhenPrimaryFails() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            SubTask t1 = assignedSubTask(11L, 101L);
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong())).thenReturn(List.of(t1));
            doThrow(new RuntimeException("primary failure"))
                    .when(subTaskDispatchService).redispatchOfflineSubTask(eq(11L), eq(101L));

            invokeReassignStaleTasks(stale);

            verify(subTaskDispatchService, times(1))
                    .redispatchOfflineSubTask(eq(11L), eq(101L));
            // G-015 B2.1：二次路径必须走不重复计数的补偿重载
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskCompensating(eq(11L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("首选失败 + 二次也失败 → 两条异常都记录，不覆盖新状态")
        void shouldLogBothExceptionsWhenAllFail() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.REVIEWER);
            SubTask t1 = assignedSubTask(11L, 101L);
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong())).thenReturn(List.of(t1));
            doThrow(new RuntimeException("primary failure"))
                    .when(subTaskDispatchService).redispatchOfflineSubTask(eq(11L), eq(101L));
            doThrow(new RuntimeException("secondary failure"))
                    .when(subTaskDispatchService).dispatchPendingSubTaskCompensating(eq(11L), any());

            invokeReassignStaleTasks(stale);

            // 两条路径都被尝试
            verify(subTaskDispatchService, times(1))
                    .redispatchOfflineSubTask(eq(11L), eq(101L));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskCompensating(eq(11L), eq(AgentRole.REVIEWER));
        }

        @Test
        @DisplayName("原 Agent role 为 null → 二次选人回退 EXECUTOR")
        void shouldFallbackToExecutorRoleWhenStaleRoleNull() throws Exception {
            Agent stale = cliAgent(101L, null);
            SubTask t1 = assignedSubTask(11L, 101L);
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong())).thenReturn(List.of(t1));
            doThrow(new RuntimeException("primary failure"))
                    .when(subTaskDispatchService).redispatchOfflineSubTask(eq(11L), eq(101L));

            invokeReassignStaleTasks(stale);

            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskCompensating(eq(11L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("无待重分配任务 → 不调用任何 dispatch 入口")
        void shouldDoNothingWhenNoTasks() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong())).thenReturn(List.of());

            invokeReassignStaleTasks(stale);

            verify(subTaskDispatchService, never())
                    .redispatchOfflineSubTask(anyLong(), anyLong());
            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("staleAgent 为 null → 直接返回")
        void shouldReturnWhenAgentNull() throws Exception {
            invokeReassignStaleTasks(null);

            verify(subTaskDispatchService, never())
                    .redispatchOfflineSubTask(anyLong(), anyLong());
            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("OFFLINE CAS 守卫")
    class OfflineCasGuard {

        @Test
        @DisplayName("CAS 标 OFFLINE 返回 0 → 不调用 reassignStaleTasks / 不写 timeline / 不 recordFailure")
        void shouldNotProcessWhenCasFails() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            stale.setLastSeenTime(OffsetDateTime.now().minusMinutes(10));
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentService.markOfflineIfStale(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(0);  // CAS 失败

            task.checkHealth();

            verify(agentService, times(1)).markOfflineIfStale(any(), any(), anyString(), anyString(), any());
            // CAS 失败 → 不得触达任何重派入口（PAUSED 回收查询会被用到，故不断言它）
            verify(subTaskDispatchService, never()).redispatchOfflineSubTask(anyLong(), anyLong());
            verify(subTaskDispatchService, never()).dispatchPendingSubTaskCompensating(anyLong(), any());
            verify(taskTimelineService, never()).recordEvent(
                    any(), any(), eq("agent_offline"), any(), any(), any());
            verify(failureTracker, never()).recordFailure(anyLong());
        }

        @Test
        @DisplayName("CAS 标 OFFLINE 返回 1 + 有在跑任务 → 重派 + 写 timeline + recordFailure")
        void shouldProcessWhenCasSucceeds() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            stale.setLastSeenTime(OffsetDateTime.now().minusMinutes(10));
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentService.markOfflineIfStale(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(1);  // CAS 成功
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong()))
                    .thenReturn(List.of(assignedSubTask(11L, 101L)));

            task.checkHealth();

            verify(taskTimelineService, times(1)).recordEvent(
                    any(), any(), eq("agent_offline"), eq(AgentRole.EXECUTOR), eq(101L), any());
            verify(failureTracker, times(1)).recordFailure(101L);
        }

        @Test
        @DisplayName("CAS 标 OFFLINE 返回 1 + 无在跑任务（提交后静默待命）→ 不 recordFailure")
        void shouldNotRecordFailureWhenNoInFlightTasks() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            stale.setLastSeenTime(OffsetDateTime.now().minusMinutes(10));
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentService.markOfflineIfStale(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(1);  // CAS 成功
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong())).thenReturn(List.of());

            task.checkHealth();

            // 语义修正：心跳丢失但无在跑任务不视为执行失败，
            // 避免"提交后停止心跳"的客户端每完成一个任务就被计 1 次失败
            verify(taskTimelineService, times(1)).recordEvent(
                    any(), any(), eq("agent_offline"), eq(AgentRole.EXECUTOR), eq(101L), any());
            verify(failureTracker, never()).recordFailure(anyLong());
        }

        @Test
        @DisplayName("Redis TTL 仍在 → 跳过 OFFLINE 流程（heartbeat 刚到）")
        void shouldSkipWhenRedisTtlAlive() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            stale.setLastSeenTime(OffsetDateTime.now().minusMinutes(10));
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(redis.hasKey(anyString())).thenReturn(true);  // Redis TTL 仍在

            task.checkHealth();

            verify(agentService, never()).markOfflineIfStale(any(), any(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("G-015 B1 写侧守卫（租约 + 在飞宽限）")
    class G015B1Guards {

        @Test
        @DisplayName("持 ACTIVE 值班租约 → 跳过离线处置（不 CAS / 不重派 / 不计失败）")
        void shouldSkipWhenActiveDutyLease() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentDutyLeaseService.isOnDuty(101L)).thenReturn(true);

            task.checkHealth();

            verify(agentService, never()).markOfflineIfStale(any(), any(), anyString(), anyString(), any());
            verify(subTaskService, never()).listInFlightAssignedOrInProgress(anyLong());
            verify(failureTracker, never()).recordFailure(anyLong());
        }

        @Test
        @DisplayName("持在飞子任务 → CAS 使用宽限 cutoff（30 分钟），而非 5 分钟")
        void shouldUseGraceCutoffWhenInFlight() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(subTaskService.existsInFlightAssignedOrInProgress(anyLong())).thenReturn(true);
            when(agentService.markOfflineIfStale(any(), any(), anyString(), anyString(), any())).thenReturn(0);

            task.checkHealth();

            ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(agentService, times(1))
                    .markOfflineIfStale(eq(101L), cutoffCaptor.capture(), anyString(), anyString(), any());
            OffsetDateTime now = OffsetDateTime.now();
            assertThat(cutoffCaptor.getValue())
                    .isAfter(now.minusMinutes(31))
                    .isBefore(now.minusMinutes(29));
        }

        @Test
        @DisplayName("无在飞子任务 → CAS 使用常规 cutoff（5 分钟）")
        void shouldUseNormalCutoffWhenNoInFlight() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(subTaskService.existsInFlightAssignedOrInProgress(anyLong())).thenReturn(false);
            when(agentService.markOfflineIfStale(any(), any(), anyString(), anyString(), any())).thenReturn(0);

            task.checkHealth();

            ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(agentService, times(1))
                    .markOfflineIfStale(eq(101L), cutoffCaptor.capture(), anyString(), anyString(), any());
            OffsetDateTime now = OffsetDateTime.now();
            assertThat(cutoffCaptor.getValue())
                    .isAfter(now.minusMinutes(6))
                    .isBefore(now.minusMinutes(4));
        }

        @Test
        @DisplayName("租约查询异常 → 按无租约处理，继续走离线流程（fail-close）")
        void shouldTreatLeaseQueryFailureAsNoLease() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentDutyLeaseService.isOnDuty(101L)).thenThrow(new RuntimeException("lease db down"));
            when(subTaskService.existsInFlightAssignedOrInProgress(anyLong())).thenReturn(false);
            when(agentService.markOfflineIfStale(any(), any(), anyString(), anyString(), any())).thenReturn(0);

            task.checkHealth();

            verify(agentService, times(1)).markOfflineIfStale(any(), any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("在飞子任务查询异常 → 按无在飞处理，用常规 cutoff（不放宽）")
        void shouldTreatInFlightQueryFailureAsNoInFlight() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(subTaskService.existsInFlightAssignedOrInProgress(anyLong())).thenThrow(new RuntimeException("db down"));
            when(agentService.markOfflineIfStale(any(), any(), anyString(), anyString(), any())).thenReturn(0);

            task.checkHealth();

            ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(agentService, times(1))
                    .markOfflineIfStale(eq(101L), cutoffCaptor.capture(), anyString(), anyString(), any());
            OffsetDateTime now = OffsetDateTime.now();
            assertThat(cutoffCaptor.getValue())
                    .isAfter(now.minusMinutes(6))
                    .isBefore(now.minusMinutes(4));
        }
    }

    @Nested
    @DisplayName("G-015 B2.3 在飞任务保留归属 + PAUSED 超宽限回收")
    class G015B23Retention {

        @Test
        @DisplayName("IN_PROGRESS 子任务 → 置 PAUSED 保留归属，不调用任何重派入口")
        void shouldPauseInProgressTaskInsteadOfReassign() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong()))
                    .thenReturn(List.of(subTask(11L, 101L, SubTaskStatus.IN_PROGRESS)));

            invokeReassignStaleTasks(stale);

            verify(subTaskService, times(1)).pause(11L);
            verify(subTaskDispatchService, never()).redispatchOfflineSubTask(anyLong(), anyLong());
            verify(subTaskDispatchService, never()).dispatchPendingSubTaskCompensating(anyLong(), any());
        }

        @Test
        @DisplayName("IN_PROGRESS 与 ASSIGNED 混合 → 前者 PAUSED、后者重派")
        void shouldSplitByStatus() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong()))
                    .thenReturn(List.of(subTask(11L, 101L, SubTaskStatus.IN_PROGRESS),
                            subTask(12L, 101L, SubTaskStatus.ASSIGNED)));

            invokeReassignStaleTasks(stale);

            verify(subTaskService, times(1)).pause(11L);
            verify(subTaskDispatchService, times(1)).redispatchOfflineSubTask(eq(12L), eq(101L));
        }

        @Test
        @DisplayName("置 PAUSED 失败 → 只记 failed，不抛异常也不误重派")
        void shouldToleratePauseFailure() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(subTaskService.listInFlightAssignedOrInProgress(anyLong()))
                    .thenReturn(List.of(subTask(11L, 101L, SubTaskStatus.IN_PROGRESS)));
            doThrow(new RuntimeException("state changed"))
                    .when(subTaskService).pause(11L);

            invokeReassignStaleTasks(stale);

            verify(subTaskDispatchService, never()).redispatchOfflineSubTask(anyLong(), anyLong());
        }

        @Test
        @DisplayName("PAUSED 超宽限（update_time 早于宽限）→ 经 redispatchInProgress 回收")
        void shouldReclaimExpiredPausedTask() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            SubTask paused = subTask(11L, 101L, SubTaskStatus.PAUSED);
            paused.setUpdateTime(OffsetDateTime.now().minusMinutes(45));
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(subTaskService.listPausedBefore(anyLong(), any())).thenReturn(List.of(paused));
            when(agentService.markOfflineIfStale(any(), any(), anyString(), anyString(), any())).thenReturn(0);

            task.checkHealth();

            verify(subTaskDispatchService, times(1)).redispatchInProgress(eq(11L), eq(101L));
        }

        // 说明：「PAUSED 未超宽限不回收」无法在本层用 Mockito 断言 —— 时间窗口条件
        // （update_time <= now - graceMinutes）由 listPausedBefore 的 wrapper 下推给 DB，
        // mock 的 Service 会忽略 wrapper 条件、原样返回列表，构造不出「被 SQL 过滤掉」的场景。
        // 该条件由 SQL 承担，属集成/E2E 覆盖范围（本机无 PG，记 NOT RUN）。
    }

    @Nested
    @DisplayName("P2-1 租约在岗 online_status 校正（写侧/读侧双视图分裂修复）")
    class P21LeaseStatusCorrection {

        @Test
        @DisplayName("持 ACTIVE 租约 + 心跳陈旧 → 校正 online_status 为 IDLE，不触达离线处置链")
        void shouldCorrectOnlineStatusWhenActiveLease() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentDutyLeaseService.isOnDuty(101L)).thenReturn(true);
            when(agentService.correctOnlineStatusIfStale(eq(101L), eq("IDLE"), any(), any()))
                    .thenReturn(1);

            task.checkHealth();

            // 走「先校正后 return」：不再让 DB 停留在陈旧值
            verify(agentService, times(1))
                    .correctOnlineStatusIfStale(eq(101L), eq("IDLE"), any(), any());
            // 亚于离线处置：不判死（不写 offline_reason/offline_time 的 5 参 CAS）
            verify(agentService, never())
                    .markOfflineIfStale(any(), any(), anyString(), anyString(), any());
            // 不重派 / 不写 agent_offline timeline / 不计 N11 失败
            verify(subTaskDispatchService, never()).redispatchOfflineSubTask(anyLong(), anyLong());
            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskCompensating(anyLong(), any());
            verify(taskTimelineService, never())
                    .recordEvent(any(), any(), eq("agent_offline"), any(), any(), any());
            verify(failureTracker, never()).recordFailure(anyLong());
        }

        @Test
        @DisplayName("持 ACTIVE 租约但校正 CAS 返回 0（心跳刚到/已是 IDLE）→ 仍不触达离线处置链")
        void shouldNotProcessWhenCorrectionCasMisses() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentDutyLeaseService.isOnDuty(101L)).thenReturn(true);
            when(agentService.correctOnlineStatusIfStale(eq(101L), eq("IDLE"), any(), any()))
                    .thenReturn(0);

            task.checkHealth();

            verify(agentService, times(1))
                    .correctOnlineStatusIfStale(eq(101L), eq("IDLE"), any(), any());
            verify(agentService, never())
                    .markOfflineIfStale(any(), any(), anyString(), anyString(), any());
            verify(subTaskService, never()).listInFlightAssignedOrInProgress(anyLong());
            verify(failureTracker, never()).recordFailure(anyLong());
        }

        @Test
        @DisplayName("校正 cutoff 用常规阈值 thresholdMinutes（5 分钟）而非在飞宽限（30 分钟）")
        void shouldUseThresholdCutoffNotGraceCutoff() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentDutyLeaseService.isOnDuty(101L)).thenReturn(true);
            when(agentService.correctOnlineStatusIfStale(anyLong(), anyString(), any(), any()))
                    .thenReturn(1);

            task.checkHealth();

            ArgumentCaptor<OffsetDateTime> cutoffCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(agentService, times(1))
                    .correctOnlineStatusIfStale(eq(101L), eq("IDLE"), cutoffCaptor.capture(), any());
            OffsetDateTime now = OffsetDateTime.now();
            assertThat(cutoffCaptor.getValue())
                    .isAfter(now.minusMinutes(6))
                    .isBefore(now.minusMinutes(4));
        }

        @Test
        @DisplayName("无 ACTIVE 租约 → 不调用 correctOnlineStatusIfStale（走常规判死链）")
        void shouldNotCorrectWhenNoLease() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentDutyLeaseService.isOnDuty(101L)).thenReturn(false);
            when(subTaskService.existsInFlightAssignedOrInProgress(anyLong())).thenReturn(false);
            when(agentService.markOfflineIfStale(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(0);

            task.checkHealth();

            verify(agentService, never())
                    .correctOnlineStatusIfStale(anyLong(), anyString(), any(), any());
        }

        @Test
        @DisplayName("持 ACTIVE 租约 + last_seen_time=null（NULL 也算超时）→ 仍调用校正，不触达离线链")
        void shouldCorrectWhenLastSeenNullAndActiveLease() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            stale.setLastSeenTime(null);
            when(agentService.listStaleSince(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentDutyLeaseService.isOnDuty(101L)).thenReturn(true);
            when(agentService.correctOnlineStatusIfStale(eq(101L), eq("IDLE"), any(), any()))
                    .thenReturn(1);

            task.checkHealth();

            // NULL last_seen 也计入超时（与 selectByLastSeenBefore 同口径）⇒ 必须调用校正；
            // SQL 层谓词 (last_seen_time IS NULL OR last_seen_time < cutoff) 由真机验证覆盖。
            verify(agentService, times(1))
                    .correctOnlineStatusIfStale(eq(101L), eq("IDLE"), any(), any());
            verify(agentService, never())
                    .markOfflineIfStale(any(), any(), anyString(), anyString(), any());
            verify(subTaskDispatchService, never()).redispatchOfflineSubTask(anyLong(), anyLong());
            verify(failureTracker, never()).recordFailure(anyLong());
        }
    }

    private static Agent cliAgent(Long id, AgentRole role) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("cli-agent-" + id);
        a.setRole(role);
        a.setAccessType(com.helloai.common.constant.AgentAccessType.CLI_CLIENT);
        a.setStatus(AgentStatus.ACTIVE);
        a.setOnlineStatus(AgentOnlineStatus.ONLINE);
        a.setLastSeenTime(OffsetDateTime.now().minusMinutes(10));
        return a;
    }

    private static SubTask assignedSubTask(Long id, Long agentId) {
        return subTask(id, agentId, SubTaskStatus.ASSIGNED);
    }

    private static SubTask subTask(Long id, Long agentId, SubTaskStatus status) {
        SubTask s = new SubTask();
        s.setId(id);
        s.setAssignedAgentId(agentId);
        s.setStatus(status);
        return s;
    }
}
