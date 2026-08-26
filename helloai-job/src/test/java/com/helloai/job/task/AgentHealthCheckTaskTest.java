package com.helloai.job.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.common.config.AgentHealthProperties;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
import com.helloai.core.task.service.SubTaskDispatchService;
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

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentHealthCheckTask} 单元测试（§4.1 二次选人加固）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>首选重派（弹性 fallback）成功 → 不触发二次自动选人</li>
 *   <li>首选失败 → 二次自动选人（dispatchPendingSubTaskAuto）成功</li>
 *   <li>首选失败 + 二次也失败 → 计入 failed，两条异常都记录</li>
 *   <li>CAS 标 OFFLINE 返回 0 → 不触发重派、不写 timeline、不调用 recordFailure</li>
 *   <li>Redis TTL 仍在 → 跳过 OFFLINE 流程</li>
 *   <li>offlineMinutes 配置 ≤ 0 → 禁用扫描</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentHealthCheckTask")
class AgentHealthCheckTaskTest {

    @Mock
    private AgentMapper agentMapper;
    @Mock
    private SubTaskMapper subTaskMapper;
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

    private AgentHealthProperties healthProperties;
    private AgentHealthCheckTask task;

    @BeforeEach
    void setUp() {
        healthProperties = new AgentHealthProperties();
        healthProperties.setOfflineMinutes(5);

        task = new AgentHealthCheckTask(
                agentMapper, subTaskMapper, taskTimelineService,
                agentService, subTaskDispatchService, redis,
                failureTracker, healthProperties);

        // v1.2 §阶段2：SETNX 手写锁迁 ShedLock（代理拦截，单测直建对象无锁分支）；
        // StringRedisTemplate 保留用于 isRedisAlive 心跳 TTL 二次验证
        lenient().when(redis.hasKey(anyString())).thenReturn(false);
    }

    /**
     * 通过反射调用 private 方法 reassignStaleTasks(Agent)，覆盖二次选人加固逻辑。
     * §4.1：reassignStaleTasks 已重构为按 Agent 维度调用，无需提前查找字段。
     */
    @SuppressWarnings("unchecked")
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

            verify(agentMapper, never()).selectByLastSeenBefore(any());
        }

        @Test
        @DisplayName("无超时 Agent → 直接返回")
        void shouldReturnWhenNoStaleAgent() {
            when(agentMapper.selectByLastSeenBefore(any(OffsetDateTime.class))).thenReturn(List.of());

            task.checkHealth();

            verify(agentMapper, times(1)).selectByLastSeenBefore(any(OffsetDateTime.class));
            verify(subTaskMapper, never()).selectList(any(LambdaQueryWrapper.class));
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
            when(subTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(t1));

            invokeReassignStaleTasks(stale);

            verify(subTaskDispatchService, times(1))
                    .redispatchOfflineSubTask(eq(11L), eq(101L));
            verify(subTaskDispatchService, never())
                    .dispatchPendingSubTaskAuto(anyLong(), any());
        }

        @Test
        @DisplayName("首选失败 → 二次 dispatchPendingSubTaskAuto 用 stale.role 重新选人")
        void shouldUseStaleRoleWhenPrimaryFails() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            SubTask t1 = assignedSubTask(11L, 101L);
            when(subTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(t1));
            doThrow(new RuntimeException("primary failure"))
                    .when(subTaskDispatchService).redispatchOfflineSubTask(eq(11L), eq(101L));

            invokeReassignStaleTasks(stale);

            verify(subTaskDispatchService, times(1))
                    .redispatchOfflineSubTask(eq(11L), eq(101L));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(11L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("首选失败 + 二次也失败 → 两条异常都记录，不覆盖新状态")
        void shouldLogBothExceptionsWhenAllFail() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.REVIEWER);
            SubTask t1 = assignedSubTask(11L, 101L);
            when(subTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(t1));
            doThrow(new RuntimeException("primary failure"))
                    .when(subTaskDispatchService).redispatchOfflineSubTask(eq(11L), eq(101L));
            doThrow(new RuntimeException("secondary failure"))
                    .when(subTaskDispatchService).dispatchPendingSubTaskAuto(eq(11L), any());

            invokeReassignStaleTasks(stale);

            // 两条路径都被尝试
            verify(subTaskDispatchService, times(1))
                    .redispatchOfflineSubTask(eq(11L), eq(101L));
            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(11L), eq(AgentRole.REVIEWER));
        }

        @Test
        @DisplayName("原 Agent role 为 null → 二次选人回退 EXECUTOR")
        void shouldFallbackToExecutorRoleWhenStaleRoleNull() throws Exception {
            Agent stale = cliAgent(101L, null);
            SubTask t1 = assignedSubTask(11L, 101L);
            when(subTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(t1));
            doThrow(new RuntimeException("primary failure"))
                    .when(subTaskDispatchService).redispatchOfflineSubTask(eq(11L), eq(101L));

            invokeReassignStaleTasks(stale);

            verify(subTaskDispatchService, times(1))
                    .dispatchPendingSubTaskAuto(eq(11L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("无待重分配任务 → 不调用任何 dispatch 入口")
        void shouldDoNothingWhenNoTasks() throws Exception {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            when(subTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

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
            when(agentMapper.selectByLastSeenBefore(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentMapper.markOfflineIfStale(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(0);  // CAS 失败

            task.checkHealth();

            verify(agentMapper, times(1)).markOfflineIfStale(any(), any(), anyString(), anyString(), any());
            verify(subTaskMapper, never()).selectList(any(LambdaQueryWrapper.class));
            verify(taskTimelineService, never()).recordEvent(
                    any(), any(), eq("agent_offline"), any(), any(), any());
            verify(failureTracker, never()).recordFailure(anyLong());
        }

        @Test
        @DisplayName("CAS 标 OFFLINE 返回 1 + 有在跑任务 → 重派 + 写 timeline + recordFailure")
        void shouldProcessWhenCasSucceeds() {
            Agent stale = cliAgent(101L, AgentRole.EXECUTOR);
            stale.setLastSeenTime(OffsetDateTime.now().minusMinutes(10));
            when(agentMapper.selectByLastSeenBefore(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentMapper.markOfflineIfStale(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(1);  // CAS 成功
            when(subTaskMapper.selectList(any(LambdaQueryWrapper.class)))
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
            when(agentMapper.selectByLastSeenBefore(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(agentMapper.markOfflineIfStale(any(), any(), anyString(), anyString(), any()))
                    .thenReturn(1);  // CAS 成功
            when(subTaskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

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
            when(agentMapper.selectByLastSeenBefore(any(OffsetDateTime.class))).thenReturn(List.of(stale));
            when(redis.hasKey(anyString())).thenReturn(true);  // Redis TTL 仍在

            task.checkHealth();

            verify(agentMapper, never()).markOfflineIfStale(any(), any(), anyString(), anyString(), any());
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
        SubTask s = new SubTask();
        s.setId(id);
        s.setAssignedAgentId(agentId);
        s.setStatus(SubTaskStatus.ASSIGNED);
        return s;
    }
}