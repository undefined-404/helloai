package com.helloai.core.planner;

import com.helloai.common.constant.PlanAttemptAction;
import com.helloai.common.constant.PlanAttemptStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.planner.entity.PlanAttempt;
import com.helloai.core.planner.mapper.PlanAttemptMapper;
import com.helloai.core.planner.service.PlanAttemptDispatcher.DispatchReport;
import com.helloai.core.planner.service.PlanAttemptService;
import com.helloai.core.planner.service.PlannerDecomposeAsyncService;
import com.helloai.core.planner.service.impl.PlanAttemptDispatcherImpl;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * `TD-16b` 派发器的单元测试。
 *
 * <p><b>核心断言是「不该干的事没干」</b>：派发器的危害全部来自「多干」——
 * 重复执行、把已回退的任务推回拆解中。所以三条用例分别钉住
 * 「目标已失效 ⇒ 终止而不是重派」「抢不到 ⇒ 不执行」「抢到 ⇒ 只执行一次」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlanAttemptDispatcher（TD-16b）")
class PlanAttemptDispatcherImplTest {

    private static final Long TASK_ID = 100L;

    @Mock
    private PlanAttemptService planAttemptService;

    @Mock
    private PlanAttemptMapper planAttemptMapper;

    @Mock
    private PlannerDecomposeAsyncService plannerDecomposeAsyncService;

    @Mock
    private TaskService taskService;

    private PlanAttemptDispatcherImpl dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new PlanAttemptDispatcherImpl(
                planAttemptService, planAttemptMapper, plannerDecomposeAsyncService, taskService);
        when(planAttemptService.reclaimHanging(anyInt())).thenReturn(0);
    }

    @Test
    @DisplayName("任务已离开 PLANNING ⇒ 判死而不是重派（不能把已回退的任务偷偷推回拆解中）")
    void shouldAbandonWhenTaskLeftPlanning() {
        PlanAttempt a = pendingDecompose(1L, 1);
        when(planAttemptMapper.listPending(50)).thenReturn(List.of(a));
        when(planAttemptMapper.listRetryable(any(), anyInt())).thenReturn(List.of());
        when(taskService.getView(TASK_ID)).thenReturn(task(TaskStatus.IN_PROGRESS));
        when(planAttemptService.failTerminal(eq(1L), eq(1), anyString())).thenReturn(true);

        DispatchReport report = dispatcher.dispatchOnce(50);

        assertThat(report.abandoned()).isEqualTo(1);
        assertThat(report.redispatched()).isZero();
        verify(plannerDecomposeAsyncService, never()).executeDecompose(anyLong());
    }

    @Test
    @DisplayName("任务不存在 ⇒ 同样判死（不能重派给一个已经没有主体的任务）")
    void shouldAbandonWhenTaskMissing() {
        PlanAttempt a = pendingDecompose(1L, 1);
        when(planAttemptMapper.listPending(50)).thenReturn(List.of(a));
        when(planAttemptMapper.listRetryable(any(), anyInt())).thenReturn(List.of());
        when(taskService.getView(TASK_ID)).thenReturn(null);
        when(planAttemptService.failTerminal(eq(1L), eq(1), anyString())).thenReturn(true);

        DispatchReport report = dispatcher.dispatchOnce(50);

        assertThat(report.abandoned()).isEqualTo(1);
        verify(plannerDecomposeAsyncService, never()).executeDecompose(anyLong());
    }

    @Test
    @DisplayName("CAS 抢不到（别的实例已接）⇒ 不执行 —— 这就是「重复派发只执行一次」")
    void shouldSkipWhenClaimLoses() {
        PlanAttempt a = pendingDecompose(1L, 1);
        when(planAttemptMapper.listPending(50)).thenReturn(List.of(a));
        when(planAttemptMapper.listRetryable(any(), anyInt())).thenReturn(List.of());
        when(taskService.getView(TASK_ID)).thenReturn(task(TaskStatus.PLANNING));
        when(planAttemptService.claim(eq(1L), anyString(), any())).thenReturn(false);

        DispatchReport report = dispatcher.dispatchOnce(50);

        assertThat(report.redispatched()).isZero();
        verify(plannerDecomposeAsyncService, never()).executeDecompose(anyLong());
    }

    @Test
    @DisplayName("CAS 抢到 ⇒ 触发一次执行")
    void shouldRedispatchWhenClaimWins() {
        PlanAttempt a = pendingDecompose(1L, 1);
        when(planAttemptMapper.listPending(50)).thenReturn(List.of(a));
        when(planAttemptMapper.listRetryable(any(), anyInt())).thenReturn(List.of());
        when(taskService.getView(TASK_ID)).thenReturn(task(TaskStatus.PLANNING));
        when(planAttemptService.claim(eq(1L), anyString(), any())).thenReturn(true);

        DispatchReport report = dispatcher.dispatchOnce(50);

        assertThat(report.redispatched()).isEqualTo(1);
        verify(plannerDecomposeAsyncService).executeDecompose(TASK_ID);
    }

    @Test
    @DisplayName("非首拆动作（展开/确认/废除）不由本派发器接管")
    void shouldSkipNonDecomposeActions() {
        PlanAttempt a = pendingDecompose(1L, 1);
        a.setAction(PlanAttemptAction.REGENERATE);
        when(planAttemptMapper.listPending(50)).thenReturn(List.of(a));
        when(planAttemptMapper.listRetryable(any(), anyInt())).thenReturn(List.of());

        DispatchReport report = dispatcher.dispatchOnce(50);

        assertThat(report.redispatched()).isZero();
        assertThat(report.abandoned()).isZero();
        // 连任务状态都不该查——说明在动作类型处就短路了。
        verify(taskService, never()).getView(anyLong());
        verify(plannerDecomposeAsyncService, never()).executeDecompose(isNull());
    }

    private PlanAttempt pendingDecompose(Long id, int attemptNo) {
        PlanAttempt a = new PlanAttempt();
        a.setId(id);
        a.setTaskId(TASK_ID);
        a.setBatchKey("k-" + id);
        a.setAction(PlanAttemptAction.DECOMPOSE);
        a.setStatus(PlanAttemptStatus.PENDING);
        a.setAttemptNo(attemptNo);
        return a;
    }

    private TaskView task(TaskStatus status) {
        return new TaskView(TASK_ID, "t", "d", null, null, null, null, null,
                status, null, null, null, null, null, null, null);
    }
}
