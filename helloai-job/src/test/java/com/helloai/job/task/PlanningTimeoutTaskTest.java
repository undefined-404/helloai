package com.helloai.job.task;

import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.helloai.common.config.PlannerDecomposeProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 *   <li>无超时记录 → 跳过（不做 CAS 回退）</li>
 *   <li>单条超时 → CAS 回退 PENDING + 记录 task_plan_timeout_recovered</li>
 *   <li>CAS 失败（状态已变化）→ 不记录 timeline（与异步成功路径互斥）</li>
 *   <li>多条超时 → 逐条回收，单条失败不中断</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanningTimeoutTask")
class PlanningTimeoutTaskTest {

    @Mock
    private TaskService taskService;
    @Mock
    private TaskTimelineService taskTimelineService;
    @Mock
    private PlannerDecomposeProperties plannerDecomposeProperties;

    private PlanningTimeoutTask task;

    @SuppressWarnings("unchecked")
    private final LambdaUpdateChainWrapper<Task> taskUpdateChain = mock(LambdaUpdateChainWrapper.class);

    @BeforeEach
    void setUp() {
        task = new PlanningTimeoutTask(
                taskService, taskTimelineService, plannerDecomposeProperties);

        // 超时阈值读配置，默认桩为 10 分钟
        lenient().when(plannerDecomposeProperties.getPlanningTimeoutMinutes()).thenReturn(10);
        // 2026-11-08 订正：`recover()` 早已改走 `taskService.recoverTimedOutDecompose(id)`（`V146`），
        // 不再用 lambdaUpdate 链。原测试仍 stub/verify 那条链，导致三条用例里两条恒失败
        // （另一条 `shouldSkipWhenCasLost` 是「碰巧通过」—— mock 默认返回 null 恰好等于 CAS 落空）。
        // 现按真实契约重写，详见各用例。
    }

    @Nested
    @DisplayName("前置条件短路")
    class Precondition {

        @Test
        @DisplayName("无超时记录 → 跳过")
        void shouldSkipWhenNoTimedOut() {
            when(taskService.listTimedOutPlanning(any(), anyInt()))
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
            when(taskService.listTimedOutPlanning(any(), anyInt()))
                    .thenReturn(List.of(planningTask(1L)));
            // 契约：返回「被推到哪个状态」；null = CAS 落空（状态已变化）
            when(taskService.recoverTimedOutDecompose(1L)).thenReturn(TaskStatus.PENDING);

            task.scan();

            verify(taskService).recoverTimedOutDecompose(1L);
            verify(taskTimelineService).recordEvent(
                    eq(1L), isNull(), eq("task_plan_timeout_recovered"),
                    eq(AgentRole.PLANNER), isNull(), anyMap());
        }

        @Test
        @DisplayName("CAS 失败（状态已变化）→ 跳过且不记录 timeline")
        void shouldSkipWhenCasLost() {
            when(taskService.listTimedOutPlanning(any(), anyInt()))
                    .thenReturn(List.of(planningTask(1L)));
            when(taskService.recoverTimedOutDecompose(1L)).thenReturn(null);

            task.scan();

            verify(taskTimelineService, never()).recordEvent(
                    anyLong(), any(), anyString(), any(), any(), anyMap());
        }

        @Test
        @DisplayName("多条超时 → 逐条回收，单条失败不中断")
        void shouldContinueOnSingleFailure() {
            when(taskService.listTimedOutPlanning(any(), anyInt()))
                    .thenReturn(List.of(planningTask(1L), planningTask(2L)));
            // 第一条抛异常（模拟 DB 抖动），第二条正常回收
            when(taskService.recoverTimedOutDecompose(1L))
                    .thenThrow(new RuntimeException("synthetic failure"));
            when(taskService.recoverTimedOutDecompose(2L)).thenReturn(TaskStatus.PENDING);

            task.scan();

            // 第二条必须被处理 —— 单条失败不能中断同轮
            verify(taskService).recoverTimedOutDecompose(2L);
            verify(taskTimelineService).recordEvent(
                    eq(2L), isNull(), eq("task_plan_timeout_recovered"),
                    eq(AgentRole.PLANNER), isNull(), anyMap());
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
