package com.helloai.core.task.listener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.shared.event.SubTaskCompletedEvent;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * 子任务完成闭环监听器（V27 内循环收尾）。
 *
 * <p>{@link SubTaskCompletedEvent}（complete → REVIEW→DONE）事务提交后异步执行：</p>
 * <ol>
 *     <li><b>解锁下游</b>：查同 Task 下 PENDING 且 {@code depends_on} 包含本子任务的节点，
 *         逐个尝试 {@code dispatchPendingSubTaskAuto}（其内部 ready 守卫会自动过滤仍未就绪的）</li>
 *     <li><b>Task 自动收尾</b>：同 Task 全部有效子任务均为 DONE/CANCELLED 时，
 *         幂等推进 Task→DONE</li>
 * </ol>
 *
 * <p>独立监听器承接，避免 {@code SubTaskService} 反向注入 {@code SubTaskDispatchService}
 * 造成循环依赖（与 {@code SubTaskAutoExecutionDispatcher} 的解耦思路一致）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskCompletionListener {

    private final SubTaskService subTaskService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final TaskMapper taskMapper;
    private final TaskTimelineService taskTimelineService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubTaskCompleted(SubTaskCompletedEvent event) {
        try {
            unlockDownstream(event.getSubTaskId(), event.getTaskId());
        } catch (Exception e) {
            log.warn("解锁下游依赖节点异常: subTaskId={}, err={}", event.getSubTaskId(), e.getMessage());
        }
        try {
            tryCloseTask(event.getTaskId());
        } catch (Exception e) {
            log.warn("Task 自动收尾异常: taskId={}, err={}", event.getTaskId(), e.getMessage());
        }
    }

    /** 解锁下游：查同 Task PENDING 且依赖包含本子任务的节点，逐个尝试自动分发。 */
    private void unlockDownstream(Long completedSubTaskId, Long taskId) {
        if (taskId == null) {
            return;
        }
        List<SubTask> pending = subTaskService.list(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getTaskId, taskId)
                .eq(SubTask::getStatus, SubTaskStatus.PENDING));
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (SubTask downstream : pending) {
            if (!downstream.dependsOnIdList().contains(completedSubTaskId)) {
                continue;
            }
            try {
                // ready 守卫会自动过滤依赖仍未全部 DONE 的节点，无需在此重复判定
                subTaskDispatchService.dispatchPendingSubTaskAuto(downstream.getId(), AgentRole.EXECUTOR);
            } catch (Exception e) {
                // V41：失败原因写 timeline，让"无可用候选/状态冲突"可见（此前仅 warn 日志，
                // 子任务无声卡在 PENDING，用户只能靠手动排查）；孤儿巡检（5 分钟阈值）负责重试
                log.warn("下游节点分发失败（保持 PENDING 等兜底）: subTaskId={}, err={}",
                        downstream.getId(), e.getMessage());
                try {
                    taskTimelineService.recordEvent(taskId, downstream.getId(), "sub_task_dispatch_deferred",
                            AgentRole.SYSTEM, null,
                            Map.of("reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                                    "waitFor", "pending_orphan_scan"));
                } catch (Exception timelineEx) {
                    log.debug("记录 sub_task_dispatch_deferred 事件失败: subTaskId={}, err={}",
                            downstream.getId(), timelineEx.getMessage());
                }
            }
        }
    }

    /** Task 自动收尾：全部有效子任务均 DONE/CANCELLED 时幂等推进 Task→DONE。 */
    private void tryCloseTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        Task task = taskMapper.selectById(taskId);
        if (task == null || task.getStatus() == TaskStatus.DONE || task.getStatus() == TaskStatus.CANCELLED) {
            return;
        }
        List<SubTask> all = subTaskService.list(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getTaskId, taskId));
        if (all == null || all.isEmpty()) {
            return;
        }
        // 草案态子任务（PENDING_PLAN_REVIEW）不参与收尾判定：仅统计已确认进入生命周期的子任务
        long effective = 0;
        for (SubTask st : all) {
            if (st.getStatus() == SubTaskStatus.PENDING_PLAN_REVIEW) {
                continue;
            }
            effective++;
            if (st.getStatus() != SubTaskStatus.DONE && st.getStatus() != SubTaskStatus.CANCELLED) {
                return; // 尚有未收尾子任务
            }
        }
        if (effective == 0) {
            return;
        }
        // 幂等 CAS：仅当当前状态仍非 DONE 时推进，避免并发重复收尾
        int updated = taskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .ne(Task::getStatus, TaskStatus.DONE)
                .set(Task::getStatus, TaskStatus.DONE));
        if (updated > 0) {
            taskTimelineService.recordEvent(taskId, null, "task_auto_completed",
                    AgentRole.SYSTEM, null, Map.of("trigger", "all_sub_tasks_done"));
            log.info("Task 自动收尾完成: taskId={}", taskId);
            // V32：CAS 赢家唯一，恰好保证整合报告只被触发一次。此处已无事务上下文，
            // 消费方（TaskFinalReportService）用普通 @EventListener + @Async 承接。
            applicationEventPublisher.publishEvent(new TaskAutoCompletedEvent(taskId));
        }
    }
}
