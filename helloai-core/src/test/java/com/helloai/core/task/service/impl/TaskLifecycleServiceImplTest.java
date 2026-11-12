package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskFinalReportService;
import com.helloai.core.task.service.TaskTimelineService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskLifecycleServiceImpl}：任务收尾 + 派生完成态传播（`ADR-008` §5）。
 *
 * <p>覆盖三类容易静默出错的判据：</p>
 * <ul>
 *   <li>草案态子任务不参与收尾（否则确认前任务会被提前判完成）</li>
 *   <li>派生 Task「完成但零产出」不能记成父任务完成，必须转 `RESTRUCTURING`</li>
 *   <li>传播必须幂等：父不在 `AWAITING_DERIVED` 或并发已推进时不重复写</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskLifecycleServiceImpl")
class TaskLifecycleServiceImplTest {

    @Mock TaskMapper taskMapper;
    @Mock SubTaskService subTaskService;
    @Mock TaskTimelineService taskTimelineService;
    @Mock ApplicationEventPublisher applicationEventPublisher;
    @Mock TaskFinalReportService taskFinalReportService;
    @Mock AgentDispatchProperties dispatchProperties;

    @InjectMocks TaskLifecycleServiceImpl service;

    /** Lambda 条件构造器需要 TableInfo 缓存（脱离 Spring 的 MyBatis-Plus 单测通病）。 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Task.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SubTask.class);
    }

    private static Task task(long id, TaskStatus status) {
        Task t = new Task();
        t.setId(id);
        t.setStatus(status);
        return t;
    }

    private static Task derivedTask(long id, TaskStatus status, long parentSubTaskId) {
        Task t = task(id, status);
        t.setParentSubTaskId(parentSubTaskId);
        return t;
    }

    private static SubTask subTask(long id, long taskId, SubTaskStatus status) {
        SubTask st = new SubTask();
        st.setId(id);
        st.setTaskId(taskId);
        st.setStatus(status);
        return st;
    }

    // ══════════════════════════════════════════════════════════════
    //  收尾判据
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("全部有效子任务终态 → CAS 收尾 + 只发一次自动完成事件")
    void shouldCloseTaskWhenAllEffectiveSubTasksTerminal() {
        Task row = task(9L, TaskStatus.IN_PROGRESS);
        when(taskMapper.selectById(9L)).thenReturn(row);
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(
                subTask(1L, 9L, SubTaskStatus.DONE),
                subTask(2L, 9L, SubTaskStatus.CANCELLED)));
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        assertThat(service.tryCloseTask(9L)).isTrue();

        verify(applicationEventPublisher).publishEvent(any(TaskAutoCompletedEvent.class));
        verify(taskTimelineService).recordEvent(eq(9L), eq(null),
                eq(TimelineEventCode.TASK_AUTO_COMPLETED.code()), any(), any(), any());
    }

    @Test
    @DisplayName("非终态执行行不参与收尾：仅 PENDING 子任务时不得收尾")
    void shouldNotCloseWhenOnlyDraftSubTasksExist() {
        when(taskMapper.selectById(9L)).thenReturn(task(9L, TaskStatus.IN_PROGRESS));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(
                subTask(1L, 9L, SubTaskStatus.PENDING)));

        assertThat(service.tryCloseTask(9L)).isFalse();
        verify(taskMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("非 IN_PROGRESS（草案取消后的待确认/待规划）即便子任务全终态也不收尾")
    void shouldNotCloseWhenTaskNotInProgress() {
        when(taskMapper.selectById(9L)).thenReturn(task(9L, TaskStatus.AWAITING_PLAN_CONFIRM));

        assertThat(service.tryCloseTask(9L)).isFalse();
        verify(subTaskService, never()).list(any(Wrapper.class));
        verify(taskMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("仍有在途子任务 → 不收尾")
    void shouldNotCloseWhenAnyEffectiveSubTaskRunning() {
        when(taskMapper.selectById(9L)).thenReturn(task(9L, TaskStatus.IN_PROGRESS));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(
                subTask(1L, 9L, SubTaskStatus.DONE),
                subTask(2L, 9L, SubTaskStatus.AWAITING_DERIVED)));

        assertThat(service.tryCloseTask(9L)).isFalse();
    }

    @Test
    @DisplayName("CAS 未命中（并发已被收尾）→ 返回 false 且不重复发事件")
    void shouldNotPublishEventWhenCasLost() {
        when(taskMapper.selectById(9L)).thenReturn(task(9L, TaskStatus.IN_PROGRESS));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(
                subTask(1L, 9L, SubTaskStatus.DONE)));
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        assertThat(service.tryCloseTask(9L)).isFalse();
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    // ══════════════════════════════════════════════════════════════
    //  派生完成态传播
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("派生 DONE 且有 DONE 子任务 → 父 AWAITING_DERIVED 推进 DONE + 写完成事件")
    void shouldPropagateDoneWhenDerivedHasOutput() {
        Task derived = derivedTask(20L, TaskStatus.DONE, 55L);
        when(subTaskService.getById(55L)).thenReturn(subTask(55L, 3L, SubTaskStatus.AWAITING_DERIVED));
        when(subTaskService.count(any(Wrapper.class))).thenReturn(1L);
        // 父任务 3 收尾判定：不是 DONE，随后 list 返回空 ⇒ tryCloseTask 直接返回 false
        when(taskMapper.selectById(3L)).thenReturn(task(3L, TaskStatus.IN_PROGRESS));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.propagateDerivedCompletion(derived, "sync")).isTrue();

        verify(subTaskService).changeStatus(eq(55L), eq(SubTaskStatus.DONE), eq(null), any());
        verify(taskTimelineService).recordEvent(eq(3L), eq(55L),
                eq(TimelineEventCode.SUB_TASK_DERIVED_COMPLETED.code()), any(), any(), any());
    }

    @Test
    @DisplayName("派生 DONE 但零 DONE 子任务 → 父转 RESTRUCTURING（不把空产出记成完成）")
    void shouldPropagateRestructuringWhenDerivedHasNoOutput() {
        Task derived = derivedTask(20L, TaskStatus.DONE, 55L);
        when(subTaskService.getById(55L)).thenReturn(subTask(55L, 3L, SubTaskStatus.AWAITING_DERIVED));
        when(subTaskService.count(any(Wrapper.class))).thenReturn(0L);
        when(taskMapper.selectById(3L)).thenReturn(task(3L, TaskStatus.IN_PROGRESS));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.propagateDerivedCompletion(derived, "reconcile")).isTrue();

        verify(subTaskService).changeStatus(eq(55L), eq(SubTaskStatus.RESTRUCTURING), eq(null), any());
        verify(taskTimelineService).recordEvent(eq(3L), eq(55L),
                eq(TimelineEventCode.SUB_TASK_DERIVED_FAILED.code()), any(), any(), any());
    }

    @Test
    @DisplayName("派生 Task 被取消 → 父转 RESTRUCTURING（ADR-008 §13.4 失败收敛）")
    void shouldPropagateRestructuringWhenDerivedCancelled() {
        Task derived = derivedTask(20L, TaskStatus.CANCELLED, 55L);
        when(subTaskService.getById(55L)).thenReturn(subTask(55L, 3L, SubTaskStatus.AWAITING_DERIVED));
        when(taskMapper.selectById(3L)).thenReturn(task(3L, TaskStatus.CANCELLED));

        assertThat(service.propagateDerivedCompletion(derived, "sync")).isTrue();

        verify(subTaskService).changeStatus(eq(55L), eq(SubTaskStatus.RESTRUCTURING), eq(null), any());
    }

    @Test
    @DisplayName("父 SubTask 已不在 AWAITING_DERIVED → 幂等跳过，不重复改状态")
    void shouldSkipWhenParentAlreadyAdvanced() {
        Task derived = derivedTask(20L, TaskStatus.DONE, 55L);
        when(subTaskService.getById(55L)).thenReturn(subTask(55L, 3L, SubTaskStatus.DONE));

        assertThat(service.propagateDerivedCompletion(derived, "sync")).isFalse();
        verify(subTaskService, never()).changeStatus(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("无父锚（根任务）或派生 Task 未终态 → 不传播")
    void shouldSkipWhenNoParentAnchorOrNotTerminal() {
        Task root = task(20L, TaskStatus.DONE);
        assertThat(service.propagateDerivedCompletion(root, "sync")).isFalse();

        Task running = derivedTask(21L, TaskStatus.IN_PROGRESS, 55L);
        assertThat(service.propagateDerivedCompletion(running, "sync")).isFalse();
    }

    @Test
    @DisplayName("并发下 changeStatus 抛异常 → 视为已被推进，返回 false")
    void shouldSwallowConcurrentChangeStatusFailure() {
        Task derived = derivedTask(20L, TaskStatus.DONE, 55L);
        when(subTaskService.getById(55L)).thenReturn(subTask(55L, 3L, SubTaskStatus.AWAITING_DERIVED));
        when(subTaskService.count(any(Wrapper.class))).thenReturn(1L);
        org.mockito.Mockito.doThrow(new com.helloai.common.base.BizException("并发修改，请重试"))
                .when(subTaskService).changeStatus(eq(55L), any(), eq(null), any());

        assertThat(service.propagateDerivedCompletion(derived, "reconcile")).isFalse();
    }

    // ══════════════════════════════════════════════════════════════
    //  对账三项
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("对账①：扫描结果逐条重放收尾，成功条数即修正数")
    void shouldReplaySettledTasks() {
        when(taskMapper.selectSettledNonTerminalTaskIds(50)).thenReturn(List.of(9L));
        when(taskMapper.selectById(9L)).thenReturn(task(9L, TaskStatus.IN_PROGRESS));
        when(subTaskService.list(any(Wrapper.class))).thenReturn(List.of(
                subTask(1L, 9L, SubTaskStatus.DONE)));
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        assertThat(service.reconcileSettledTasks(50)).isEqualTo(1);
        verify(taskTimelineService).recordEvent(eq(9L), eq(null),
                eq(TimelineEventCode.TASK_LIFECYCLE_RECONCILED.code()), any(), any(),
                eq(Map.of("check", "settled_task")));
    }

    @Test
    @DisplayName("对账②：终态派生任务逐条重放传播")
    void shouldReplayDerivations() {
        Task derived = derivedTask(20L, TaskStatus.DONE, 55L);
        when(taskMapper.selectTerminalDerivationsAwaitingParent(50)).thenReturn(List.of(derived));
        when(subTaskService.getById(55L)).thenReturn(subTask(55L, 3L, SubTaskStatus.AWAITING_DERIVED));
        when(subTaskService.count(any(Wrapper.class))).thenReturn(1L);
        when(taskMapper.selectById(3L)).thenReturn(task(3L, TaskStatus.CANCELLED));

        assertThat(service.reconcileDerivations(50)).isEqualTo(1);
    }

    @Test
    @DisplayName("对账③：自动报告开关关闭 → 不扫描不触发")
    void shouldNotTriggerReportsWhenSwitchOff() {
        when(dispatchProperties.isAutoFinalReportEnabled()).thenReturn(false);

        assertThat(service.reconcileMissingFinalReports(50)).isZero();
        verify(taskMapper, never()).selectDoneTasksMissingFinalReport(anyInt());
        verify(taskFinalReportService, never()).onTaskAutoCompleted(any());
    }

    @Test
    @DisplayName("对账③：开关开启 → 对缺报告任务触发补生成")
    void shouldTriggerReportsWhenSwitchOn() {
        when(dispatchProperties.isAutoFinalReportEnabled()).thenReturn(true);
        when(taskMapper.selectDoneTasksMissingFinalReport(50)).thenReturn(List.of(9L));

        assertThat(service.reconcileMissingFinalReports(50)).isEqualTo(1);
        verify(taskFinalReportService).onTaskAutoCompleted(any(TaskAutoCompletedEvent.class));
    }
}
