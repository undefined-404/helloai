package com.helloai.job.task;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.helloai.common.config.PlannerDecomposeProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.service.TaskService;
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
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlanningTimeoutTask} 单元测试（拆解异步化改造兜底）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>锁被占用 → 跳过</li>
 *   <li>无超时记录 → 跳过（不做 CAS 回退）</li>
 *   <li>单条超时 → CAS 回退 PENDING + 记录 task_plan_timeout_recovered</li>
 *   <li>CAS 失败（状态已变化）→ 不记录 timeline（与异步成功路径互斥）</li>
 *   <li>多条超时 → 逐条回收，单条失败不中断</li>
 *   <li>安全释放锁：Lua 脚本比对 token（防误删他人锁）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanningTimeoutTask")
class PlanningTimeoutTaskTest {

    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskService taskService;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private PlannerDecomposeProperties plannerDecomposeProperties;

    private PlanningTimeoutTask task;

    @SuppressWarnings("unchecked")
    private final LambdaUpdateChainWrapper<Task> taskUpdateChain = mock(LambdaUpdateChainWrapper.class);

    @BeforeEach
    void setUp() {
        task = new PlanningTimeoutTask(
                taskMapper, taskService, taskTimelineService, redis, plannerDecomposeProperties);

        // 超时阈值读配置，默认桩为 10 分钟
        lenient().when(plannerDecomposeProperties.getPlanningTimeoutMinutes()).thenReturn(10);
        // 默认 tryLock 成功
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        // CAS 回退链：默认成功
        lenient().when(taskService.lambdaUpdate()).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.eq(any(), any())).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.set(any(), any())).thenReturn(taskUpdateChain);
        lenient().when(taskUpdateChain.update()).thenReturn(true);
    }

    @Nested
    @DisplayName("前置条件短路")
    class Precondition {

        @Test
        @DisplayName("锁被占用 → 跳过")
        void shouldSkipWhenLocked() {
            when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any()))
                    .thenReturn(false);

            task.scan();

            verify(taskMapper, never()).selectTimedOutPlanning(any(), anyInt());
            verify(taskService, never()).lambdaUpdate();
        }

        @Test
        @DisplayName("无超时记录 → 跳过")
        void shouldSkipWhenNoTimedOut() {
            when(taskMapper.selectTimedOutPlanning(any(), anyInt()))
                    .thenReturn(List.of());

            task.scan();

            verify(taskService, never()).lambdaUpdate();
            verify(taskTimelineService, never()).recordEvent(
                    anyLong(), any(), anyString(), any(), any(), anyMap());
        }
    }

    @Nested
    @DisplayName("超时回收")
    class TimeoutRecovery {

        @Test
        @DisplayName("单条超时 → CAS 回退 PENDING 并记录 task_plan_timeout_recovered")
        void shouldRecoverSingleTimedOut() {
            when(taskMapper.selectTimedOutPlanning(any(), anyInt()))
                    .thenReturn(List.of(planningTask(1L)));

            task.scan();

            verify(taskUpdateChain).update();
            verify(taskTimelineService).recordEvent(
                    eq(1L), isNull(), eq("task_plan_timeout_recovered"),
                    eq(AgentRole.PLANNER), isNull(), anyMap());
        }

        @Test
        @DisplayName("CAS 失败（状态已变化）→ 跳过且不记录 timeline")
        void shouldSkipWhenCasLost() {
            when(taskMapper.selectTimedOutPlanning(any(), anyInt()))
                    .thenReturn(List.of(planningTask(1L)));
            when(taskUpdateChain.update()).thenReturn(false);

            task.scan();

            verify(taskTimelineService, never()).recordEvent(
                    anyLong(), any(), anyString(), any(), any(), anyMap());
        }

        @Test
        @DisplayName("多条超时 → 逐条回收，单条失败不中断")
        void shouldContinueOnSingleFailure() {
            when(taskMapper.selectTimedOutPlanning(any(), anyInt()))
                    .thenReturn(List.of(planningTask(1L), planningTask(2L)));
            // 第一条 CAS 抛异常，第二条正常回收
            when(taskUpdateChain.update())
                    .thenThrow(new RuntimeException("synthetic failure"))
                    .thenReturn(true);

            task.scan();

            verify(taskTimelineService).recordEvent(
                    eq(2L), isNull(), eq("task_plan_timeout_recovered"),
                    eq(AgentRole.PLANNER), isNull(), anyMap());
        }

        @Test
        @DisplayName("安全释放锁：仅当 token 一致时才删（防止误删他人锁）")
        void shouldUseLuaUnlockScriptWithMatchingToken() {
            when(taskMapper.selectTimedOutPlanning(any(), anyInt()))
                    .thenReturn(List.of(planningTask(1L)));

            task.scan();

            // finally 必须调用 redis.execute(Lua, ...) 而不是 redis.delete
            verify(redis).execute(any(RedisScript.class), any(List.class), any());
            verify(redis, never()).delete((String) any());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具
    // ═══════════════════════════════════════════════════════════════

    private static Task planningTask(Long id) {
        Task t = new Task();
        t.setId(id);
        t.setTitle("task-" + id);
        t.setStatus(TaskStatus.PLANNING);
        return t;
    }
}
