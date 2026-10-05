package com.helloai.core.planner;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.planner.service.PlannerAnalysisService;
import com.helloai.core.planner.service.PlannerDecomposeAsyncService;
import com.helloai.core.planner.service.impl.PlannerAnalysisServiceImpl;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.TaskView;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.service.TaskRunningSpecService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlannerAnalysisService 单元测试（拆解异步化）：
 * decompose 同步守卫（校验 / CAS / 异步提交即返回 / 线程池拒绝回退）、
 * confirm / reject 状态流转。
 *
 * <p>LLM 拆解段用例（成功落库、JSON 解析失败、幽灵依赖、dependsOn 回写、
 * validateDependencies 等）已迁至 {@code PlannerDecomposeAsyncServiceImplTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlannerAnalysisService")
class PlannerAnalysisServiceTest {

    private static final Long TASK_ID = 100L;

    @Mock
    private TaskService taskService;

    @Mock
    private SubTaskService subTaskService;

    @Mock
    private PlannerDecomposeAsyncService plannerDecomposeAsyncService;

    @Mock
    private TaskTimelineService taskTimelineService;

    @Mock
    private SubTaskDispatchService subTaskDispatchService;

    @Mock
    private TaskRunningSpecService taskRunningSpecService;

    private PlannerAnalysisService plannerAnalysisService;

    // lambdaQuery / lambdaUpdate 链式 mock（项目内首例：手动 stub 链式返回自身）
    @SuppressWarnings("unchecked")
    private final LambdaQueryChainWrapper<SubTaskView> subTaskQueryChain = mock(LambdaQueryChainWrapper.class);

    @SuppressWarnings("unchecked")
    private final LambdaUpdateChainWrapper<TaskView> taskUpdateChain = mock(LambdaUpdateChainWrapper.class);

    @BeforeEach
    void setUp() {
        plannerAnalysisService = new PlannerAnalysisServiceImpl(
                taskService, subTaskService, plannerDecomposeAsyncService,
                taskTimelineService, subTaskDispatchService, taskRunningSpecService);

        lenient().when(subTaskService.countByTaskIdExcludingStatus(anyLong(), any())).thenReturn(0L);
        lenient().when(subTaskService.countByTaskIdAndStatus(anyLong(), any())).thenReturn(0L);

        // RM5 批 5a：状态推进改走 casStatus / updateStatus（原 lambdaUpdate 链已移除）
        lenient().when(taskService.casStatus(anyLong(), any(TaskStatus.class), any(TaskStatus.class))).thenReturn(true);
        lenient().when(taskService.updateStatus(anyLong(), any(TaskStatus.class)))
                .thenReturn(org.mockito.Mockito.mock(com.helloai.core.task.entity.Task.class));
    }

    private TaskView taskView(TaskStatus status, Integer slaMinutes) {
        return new TaskView(TASK_ID, "搭建报表模块", "需要一个日报统计模块", null, null, null, null, null,
                status, slaMinutes, null, null, null, null, null);
    }

    private TaskView pendingTask() {
        return taskView(TaskStatus.PENDING, null);
    }

    private TaskView planningTask() {
        return taskView(TaskStatus.PLANNING, null);
    }

    private SubTaskView draft(long id) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW, null, null,
                null, null, null, null, null, null, List.of());
    }

    private Page<SubTaskView> pageOf(List<SubTaskView> records) {
        Page<SubTaskView> page = new Page<>();
        page.setRecords(records);
        return page;
    }

    // ══════════════════════════════════════════════════════════════
    //  decompose：同步守卫 + 异步提交即返回
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("正常拆解：校验 + CAS 推进 PLANNING 后提交异步执行，立即返回空列表")
    void shouldSubmitAsyncAndReturnEmpty() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());

        List<SubTaskView> drafts = plannerAnalysisService.decompose(TASK_ID);

        assertThat(drafts).isEmpty();
        // CAS 推进 PLANNING
        verify(taskService).casStatus(TASK_ID, TaskStatus.PENDING, TaskStatus.PLANNING);
        // 记录异步提交 timeline
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_async_submitted"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
        // LLM 拆解转交异步服务（跨类调用激活 @Async 代理）
        verify(plannerDecomposeAsyncService).executeDecompose(TASK_ID);
    }

    @Test
    @DisplayName("线程池拒绝：回退 PENDING 并抛『排队已满』业务异常")
    void shouldRollbackWhenExecutorRejects() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        doThrow(new TaskRejectedException("队列已满"))
                .when(plannerDecomposeAsyncService).executeDecompose(TASK_ID);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("排队已满");

        // CAS 推进 + 拒绝回退各走一次 lambdaUpdate
        verify(taskService, times(2)).casStatus(anyLong(), any(TaskStatus.class), any(TaskStatus.class));
    }

    // ══════════════════════════════════════════════════════════════
    //  decompose：校验拒绝路径
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("非 PENDING 任务拒绝拆解")
    void shouldRejectDecomposeWhenTaskNotPending() {
        TaskView task = taskView(TaskStatus.IN_PROGRESS, null);
        when(taskService.getView(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 PENDING");
        verify(taskService, never()).casStatus(anyLong(), any(TaskStatus.class), any(TaskStatus.class));
        verify(plannerDecomposeAsyncService, never()).executeDecompose(anyLong());
    }

    @Test
    @DisplayName("已存在非 CANCELLED 子任务时拒绝重复拆解")
    void shouldRejectDecomposeWhenSubTasksAlreadyExist() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        when(subTaskService.countByTaskIdExcludingStatus(anyLong(), any())).thenReturn(3L);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许重复拆解");
        verify(taskService, never()).casStatus(anyLong(), any(TaskStatus.class), any(TaskStatus.class));
        // §6.100: 有非 CANCELLED 残留时不得触碰物理删除
        verify(subTaskService, never()).physicalDeleteByTaskId(anyLong());
    }

    @Test
    @DisplayName("§6.100: 仅残留 CANCELLED 旧草案时，拆解前物理删除再提交异步")
    void shouldPhysicallyDeleteCancelledDraftsBeforeRedecompose() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        // 第一次 count（非 CANCELLED）= 0，第二次 count（CANCELLED）= 2，按调用顺序 stub
        when(subTaskService.countByTaskIdExcludingStatus(anyLong(), any())).thenReturn(0L);
        when(subTaskService.countByTaskIdAndStatus(anyLong(), any())).thenReturn(2L);

        List<SubTaskView> drafts = plannerAnalysisService.decompose(TASK_ID);

        // 物理删除发生在提交异步前，同步守卫正常返回空列表
        verify(subTaskService).physicalDeleteByTaskId(TASK_ID);
        assertThat(drafts).isEmpty();
        verify(plannerDecomposeAsyncService).executeDecompose(TASK_ID);
    }

    @Test
    @DisplayName("§6.100: 无 CANCELLED 残留时不触发物理删除（正常首次拆解不受影响）")
    void shouldNotDeleteWhenNoCancelledDrafts() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());

        List<SubTaskView> drafts = plannerAnalysisService.decompose(TASK_ID);

        assertThat(drafts).isEmpty();
        verify(subTaskService, never()).physicalDeleteByTaskId(anyLong());
        verify(plannerDecomposeAsyncService).executeDecompose(TASK_ID);
    }

    @Test
    @DisplayName("CAS 失败（并发拆解中）时拒绝，不提交异步")
    void shouldRejectWhenCasLost() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        when(taskService.casStatus(anyLong(), any(TaskStatus.class), any(TaskStatus.class))).thenReturn(false);

        assertThatThrownBy(() -> plannerAnalysisService.decompose(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("其他请求拆解中");
        verify(plannerDecomposeAsyncService, never()).executeDecompose(anyLong());
    }

    // ══════════════════════════════════════════════════════════════
    //  confirmPlan / rejectPlan
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("confirm：草案批量转正 PENDING，Task → IN_PROGRESS，无条件逐条分发")
    void shouldConfirmDraftsAndDispatch() {
        TaskView task = planningTask();
        when(taskService.getView(TASK_ID)).thenReturn(task);
        List<SubTaskView> drafts = List.of(draft(1L), draft(2L));
        when(subTaskService.listViewsByTaskIdAndStatus(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW))
                .thenReturn(drafts);
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));

        List<SubTaskView> confirmed = plannerAnalysisService.confirmPlan(TASK_ID);

        assertThat(confirmed).hasSize(2);
        verify(subTaskService).changeStatus(eq(1L), eq(SubTaskStatus.PENDING), isNull(), anyMap());
        verify(subTaskService).changeStatus(eq(2L), eq(SubTaskStatus.PENDING), isNull(), anyMap());
        verify(taskService).updateStatus(TASK_ID, TaskStatus.IN_PROGRESS);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_confirmed"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
        verify(subTaskDispatchService).dispatchPendingSubTaskAuto(1L, AgentRole.EXECUTOR);
        verify(subTaskDispatchService).dispatchPendingSubTaskAuto(2L, AgentRole.EXECUTOR);
    }

    @Test
    @DisplayName("confirm：单条分发失败不阻断确认与其余分发")
    void shouldConfirmEvenWhenDispatchFails() {
        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        List<SubTaskView> drafts = List.of(draft(1L), draft(2L));
        when(subTaskService.listViewsByTaskIdAndStatus(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW))
                .thenReturn(drafts);
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));
        doThrow(new BizException("无可用 Agent"))
                .when(subTaskDispatchService).dispatchPendingSubTaskAuto(1L, AgentRole.EXECUTOR);

        List<SubTaskView> confirmed = plannerAnalysisService.confirmPlan(TASK_ID);

        assertThat(confirmed).hasSize(2);
        verify(subTaskDispatchService).dispatchPendingSubTaskAuto(2L, AgentRole.EXECUTOR);
    }

    @Test
    @DisplayName("confirm：任务带 SLA 时按 确认时刻+slaMinutes 下发子任务 deadline，先持久化再转正")
    void shouldAssignDeadlineFromTaskSlaWhenConfirming() {
        TaskView task = taskView(TaskStatus.PLANNING, 60);
        when(taskService.getView(TASK_ID)).thenReturn(task);
        List<SubTaskView> drafts = List.of(draft(1L), draft(2L));
        when(subTaskService.listViewsByTaskIdAndStatus(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW))
                .thenReturn(drafts);
        when(subTaskService.getView(anyLong())).thenAnswer(inv -> draft(inv.getArgument(0)));

        OffsetDateTime before = OffsetDateTime.now();
        plannerAnalysisService.confirmPlan(TASK_ID);
        OffsetDateTime after = OffsetDateTime.now();

        // deadline 必须在 changeStatus 前落库（changeStatus 内部重查库后全字段更新，
        // 未落库的 deadline 会被覆盖丢失），取值区间 [now, now+60min]，序列化 ISO8601 带时区偏移
        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(subTaskService, times(2)).updateDeadline(anyLong(), captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(dl -> {
            assertThat(dl).isNotNull();
            assertThat(dl).isBetween(before.plusMinutes(59), after.plusMinutes(60));
            assertThat(dl.toString()).matches(".*(Z|[+-]\\d{2}:\\d{2})$");
        });
        // 转正仍逐条执行，与 deadline 下发互不干扰
        verify(subTaskService).changeStatus(eq(1L), eq(SubTaskStatus.PENDING), isNull(), anyMap());
        verify(subTaskService).changeStatus(eq(2L), eq(SubTaskStatus.PENDING), isNull(), anyMap());
    }

    @Test
    @DisplayName("confirm：非 PLANNING 状态或无草案时拒绝")
    void shouldRejectConfirmOnIllegalState() {
        when(taskService.getView(TASK_ID)).thenReturn(pendingTask());
        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 PLANNING");

        when(taskService.getView(TASK_ID)).thenReturn(planningTask());
        when(subTaskService.listViewsByTaskIdAndStatus(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW))
                .thenReturn(List.of());
        // recoverAlreadyConfirmed 会查 PENDING 子任务，未 stub 则返回 null 导致 NPE
        when(subTaskService.listViewsByTaskIdAndStatus(TASK_ID, SubTaskStatus.PENDING))
                .thenReturn(List.of());
        assertThatThrownBy(() -> plannerAnalysisService.confirmPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有待确认的规划草案");
    }

    @Test
    @DisplayName("reject：草案全部翻 CANCELLED，Task 回退 PENDING")
    void shouldRejectDraftsAndRollbackTask() {
        TaskView task = planningTask();
        when(taskService.getView(TASK_ID)).thenReturn(task);
        when(subTaskService.listViewsByTaskIdAndStatus(TASK_ID, SubTaskStatus.PENDING_PLAN_REVIEW))
                .thenReturn(List.of(draft(1L), draft(2L), draft(3L)));

        int cancelled = plannerAnalysisService.rejectPlan(TASK_ID);

        assertThat(cancelled).isEqualTo(3);
        verify(subTaskService).changeStatus(eq(1L), eq(SubTaskStatus.CANCELLED), isNull(), anyMap());
        verify(subTaskService).changeStatus(eq(3L), eq(SubTaskStatus.CANCELLED), isNull(), anyMap());
        verify(taskService).updateStatus(TASK_ID, TaskStatus.PENDING);
        verify(taskTimelineService).recordEvent(
                eq(TASK_ID), isNull(), eq("task_plan_rejected"),
                eq(AgentRole.PLANNER), isNull(), anyMap());
    }

    @Test
    @DisplayName("reject：非 PLANNING 状态拒绝")
    void shouldRejectRejectPlanOnIllegalState() {
        TaskView task = taskView(TaskStatus.DONE, null);
        when(taskService.getView(TASK_ID)).thenReturn(task);

        assertThatThrownBy(() -> plannerAnalysisService.rejectPlan(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 PLANNING");
    }
}
