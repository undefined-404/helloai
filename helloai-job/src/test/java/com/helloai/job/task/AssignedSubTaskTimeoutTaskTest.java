package com.helloai.job.task;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AssignedSubTaskTimeoutTask} 单元测试（AgentHub ）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>无超时记录 → 跳过（不调用 dispatch）</li>
 *   <li>单条超时 → 推导 role → redispatchAssignedTimeout</li>
 *   <li>多条超时 → 逐条回收，单条失败不中断</li>
 *   <li>originalAgent 不存在 → role 回退 EXECUTOR</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AssignedSubTaskTimeoutTask")
class AssignedSubTaskTimeoutTaskTest {

    @Mock
    private SubTaskMapper subTaskMapper;
    @Mock
    private SubTaskDispatchService subTaskDispatchService;
    @Mock
    private AgentService agentService;
    @Mock
    private AgentDispatchProperties agentDispatchProperties;

    private AssignedSubTaskTimeoutTask task;

    @BeforeEach
    void setUp() {
        task = new AssignedSubTaskTimeoutTask(
                subTaskMapper, subTaskDispatchService, agentService, agentDispatchProperties);

        // 超时阈值改读配置，默认桩为原硬编码值 10 分钟
        lenient().when(agentDispatchProperties.getAssignedTimeoutMinutes()).thenReturn(10);
    }

    @Nested
    @DisplayName("前置条件短路")
    class Precondition {

        @Test
        @DisplayName("无超时记录 → 跳过")
        void shouldSkipWhenNoTimedOut() {
            when(subTaskMapper.selectTimedOutAssigned(any(), anyInt()))
                    .thenReturn(List.of());

            task.scan();

            verify(subTaskDispatchService, never())
                    .redispatchAssignedTimeout(anyLong(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("超时回收")
    class TimeoutRecovery {

        @Test
        @DisplayName("单条超时 + 原 Agent 存在 → 推导 role 并回收")
        void shouldRecoverSingleTimedOut() {
            SubTask st = subTask(1L, 101L);
            when(subTaskMapper.selectTimedOutAssigned(any(), anyInt()))
                    .thenReturn(List.of(st));

            Agent agent = agent(101L, AgentRole.PLANNER);
            when(agentService.getById(101L)).thenReturn(agent);

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .redispatchAssignedTimeout(eq(1L), eq(101L), eq(AgentRole.PLANNER));
        }

        @Test
        @DisplayName("多条超时 → 逐条回收")
        void shouldRecoverMultipleTimedOut() {
            SubTask st1 = subTask(1L, 101L);
            SubTask st2 = subTask(2L, 102L);
            SubTask st3 = subTask(3L, 103L);
            when(subTaskMapper.selectTimedOutAssigned(any(), anyInt()))
                    .thenReturn(List.of(st1, st2, st3));

            when(agentService.getById(101L)).thenReturn(agent(101L, AgentRole.EXECUTOR));
            when(agentService.getById(102L)).thenReturn(agent(102L, AgentRole.REVIEWER));
            when(agentService.getById(103L)).thenReturn(agent(103L, AgentRole.EXECUTOR));

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .redispatchAssignedTimeout(eq(1L), eq(101L), eq(AgentRole.EXECUTOR));
            verify(subTaskDispatchService, times(1))
                    .redispatchAssignedTimeout(eq(2L), eq(102L), eq(AgentRole.REVIEWER));
            verify(subTaskDispatchService, times(1))
                    .redispatchAssignedTimeout(eq(3L), eq(103L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("单条失败不中断其他子任务回收")
        void shouldContinueOnSingleFailure() {
            SubTask st1 = subTask(1L, 101L);
            SubTask st2 = subTask(2L, 102L);
            when(subTaskMapper.selectTimedOutAssigned(any(), anyInt()))
                    .thenReturn(List.of(st1, st2));

            when(agentService.getById(101L)).thenReturn(agent(101L, AgentRole.EXECUTOR));
            when(agentService.getById(102L)).thenReturn(agent(102L, AgentRole.EXECUTOR));
            doThrow(new RuntimeException("synthetic failure"))
                    .when(subTaskDispatchService)
                    .redispatchAssignedTimeout(eq(1L), eq(101L), any());

            task.scan();

            // st1 失败，但 st2 仍被尝试
            verify(subTaskDispatchService, times(1))
                    .redispatchAssignedTimeout(eq(1L), eq(101L), any());
            verify(subTaskDispatchService, times(1))
                    .redispatchAssignedTimeout(eq(2L), eq(102L), any());
        }

        @Test
        @DisplayName("原 Agent 不存在 → role 回退 EXECUTOR")
        void shouldFallbackRoleWhenAgentNotFound() {
            SubTask st = subTask(1L, 999L);
            when(subTaskMapper.selectTimedOutAssigned(any(), anyInt()))
                    .thenReturn(List.of(st));
            when(agentService.getById(999L)).thenReturn(null);

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .redispatchAssignedTimeout(eq(1L), eq(999L), eq(AgentRole.EXECUTOR));
        }

        @Test
        @DisplayName("原 Agent 存在但 role 为 null → 回退 EXECUTOR")
        void shouldFallbackRoleWhenAgentRoleNull() {
            SubTask st = subTask(1L, 101L);
            when(subTaskMapper.selectTimedOutAssigned(any(), anyInt()))
                    .thenReturn(List.of(st));

            Agent agent = agent(101L, null);
            when(agentService.getById(101L)).thenReturn(agent);

            task.scan();

            verify(subTaskDispatchService, times(1))
                    .redispatchAssignedTimeout(eq(1L), eq(101L), eq(AgentRole.EXECUTOR));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具
    // ═══════════════════════════════════════════════════════════════

    private static Agent agent(Long id, AgentRole role) {
        Agent a = new Agent();
        a.setId(id);
        a.setName("agent-" + id);
        a.setRole(role);
        return a;
    }

    private static SubTask subTask(Long id, Long agentId) {
        SubTask s = new SubTask();
        s.setId(id);
        s.setAssignedAgentId(agentId);
        s.setStatus(SubTaskStatus.ASSIGNED);
        return s;
    }
}
