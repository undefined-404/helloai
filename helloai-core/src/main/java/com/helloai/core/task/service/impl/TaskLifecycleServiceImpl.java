package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskFinalReportService;
import com.helloai.core.task.service.TaskLifecycleService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 任务生命周期收尾与派生完成态传播实现（`ADR-008` §5）。
 *
 * <p><b>为什么传播是同步的、对账只做兜底</b>：CAS 收尾成功后立刻改父，把「Y 完成 →
 * A2 完成」的窗口压到一次 DB 调用；定时对账只补「进程崩在中间」的那部分历史悬挂。
 * 反过来做（只靠对账）会让父任务在最长一个轮询周期内保持 `AWAITING_DERIVED`，
 * 下游依赖它的子任务也跟着白等。</p>
 *
 * <p><b>为什么不建生命周期 outbox</b>：完成态只有这一个广播点，为单个事件引入
 * 投递表 + 中继任务 + 消费幂等的成本，大于「同步传播 + 三项对账」的代价（`ADR-008` §18.6）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskLifecycleServiceImpl implements TaskLifecycleService {

    private final TaskMapper taskMapper;
    private final SubTaskService subTaskService;
    private final TaskTimelineService taskTimelineService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TaskFinalReportService taskFinalReportService;
    private final AgentDispatchProperties dispatchProperties;

    @Override
    public boolean tryCloseTask(Long taskId) {
        if (taskId == null) {
            return false;
        }
        Task task = taskMapper.selectById(taskId);
        if (task == null || task.getStatus() != TaskStatus.IN_PROGRESS) {
            // 只有「执行中」的任务才允许被收尾：草案取消（AWAITING_PLAN_CONFIRM → 取消草案）
            // 与已回 PENDING 的任务都会出现「子任务全终态」，那不是完成，是还没开始。
            return false;
        }
        List<SubTask> all = subTaskService.list(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getTaskId, taskId));
        if (all == null || all.isEmpty()) {
            return false;
        }
        long effective = 0;
        for (SubTask st : all) {
            effective++;
            if (st.getStatus() != SubTaskStatus.DONE && st.getStatus() != SubTaskStatus.CANCELLED) {
                return false;
            }
        }
        if (effective == 0) {
            return false;
        }
        // 幂等 CAS：仅当当前状态仍非 DONE 时推进，避免并发重复收尾
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .ne(Task::getStatus, TaskStatus.DONE)
                .set(Task::getStatus, TaskStatus.DONE));
        if (updated <= 0) {
            return false;
        }
        taskTimelineService.recordEvent(taskId, null, TimelineEventCode.TASK_AUTO_COMPLETED.code(),
                AgentRole.SYSTEM, null, Map.of("trigger", "all_sub_tasks_done"));
        log.info("Task 自动收尾完成: taskId={}", taskId);
        // CAS 赢家唯一，恰好保证整合报告只被触发一次。此处无事务上下文，
        // 消费方（TaskFinalReportService）用普通 @EventListener + @Async 承接。
        applicationEventPublisher.publishEvent(new TaskAutoCompletedEvent(taskId));
        // ADR-008：派生 Task 收尾后把完成态同步传回父 SubTask（失败不影响本任务已完成的结论）
        try {
            propagateDerivedCompletion(task, "sync");
        } catch (Exception e) {
            log.warn("派生完成态传播异常（对账任务会重放）: derivedTaskId={}, err={}",
                    taskId, e.getMessage());
        }
        return true;
    }

    @Override
    public boolean propagateDerivedCompletion(Task derivedTask, String trigger) {
        if (derivedTask == null || derivedTask.getParentSubTaskId() == null) {
            return false;
        }
        if (derivedTask.getStatus() != TaskStatus.DONE && derivedTask.getStatus() != TaskStatus.CANCELLED) {
            return false;
        }
        SubTask parent = subTaskService.getById(derivedTask.getParentSubTaskId());
        if (parent == null || parent.getStatus() != SubTaskStatus.AWAITING_DERIVED) {
            return false;
        }
        SubTaskStatus target = resolveParentTarget(derivedTask);
        try {
            // 走状态机 + 乐观锁：并发重复传播时只有一次成功，其余抛并发异常
            subTaskService.changeStatus(parent.getId(), target, null,
                    Map.of("derivedTaskId", derivedTask.getId()));
        } catch (Exception e) {
            log.info("派生完成态传播跳过（父状态已被并发推进）: parentSubTaskId={}, derivedTaskId={}, err={}",
                    parent.getId(), derivedTask.getId(), e.getMessage());
            return false;
        }
        String eventCode = target == SubTaskStatus.DONE
                ? TimelineEventCode.SUB_TASK_DERIVED_COMPLETED.code()
                : TimelineEventCode.SUB_TASK_DERIVED_FAILED.code();
        try {
            taskTimelineService.recordEvent(parent.getTaskId(), parent.getId(), eventCode,
                    AgentRole.SYSTEM, null,
                    Map.of("derivedTaskId", derivedTask.getId(),
                            "derivedTaskStatus", derivedTask.getStatus().name(),
                            "trigger", trigger));
        } catch (Exception e) {
            log.warn("派生完成态时间线写入失败: parentSubTaskId={}, err={}",
                    parent.getId(), e.getMessage());
        }
        log.info("派生完成态已传播: derivedTaskId={}, parentSubTaskId={}, target={}, trigger={}",
                derivedTask.getId(), parent.getId(), target, trigger);
        // 父 SubTask 完成可能让父任务全部收尾——递归与派生链同深（depth ≤ 2，最多两级）
        tryCloseTask(parent.getTaskId());
        return true;
    }

    /**
     * 父 SubTask 的收敛目标（`ADR-008` §4 / §13.4）：
     *
     * <ul>
     *   <li>派生 Task 被取消 ⇒ `RESTRUCTURING`（废除 = 需要人再看一眼）</li>
     *   <li>派生 Task 已完成但没有任何 `DONE` 子任务（全被取消）⇒ `RESTRUCTURING`：
     *       平台没有任务级 `FAILED`，若照旧置 `DONE` 会把「什么都没产出」记成完成，
     *       父任务链路上也没有任何环节能看见失败</li>
     *   <li>其余 ⇒ `DONE`（正常完成传播）</li>
     * </ul>
     */
    private SubTaskStatus resolveParentTarget(Task derivedTask) {
        if (derivedTask.getStatus() == TaskStatus.CANCELLED) {
            return SubTaskStatus.RESTRUCTURING;
        }
        long doneCount = subTaskService.count(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getTaskId, derivedTask.getId())
                .eq(SubTask::getStatus, SubTaskStatus.DONE));
        return doneCount > 0 ? SubTaskStatus.DONE : SubTaskStatus.RESTRUCTURING;
    }

    @Override
    public int reconcileSettledTasks(int limit) {
        List<Long> taskIds = taskMapper.selectSettledNonTerminalTaskIds(limit);
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        int fixed = 0;
        for (Long taskId : taskIds) {
            try {
                if (tryCloseTask(taskId)) {
                    fixed++;
                    recordReconciled(taskId, null, "settled_task");
                }
            } catch (Exception e) {
                log.error("生命周期对账①收尾失败: taskId={}", taskId, e);
            }
        }
        if (fixed > 0) {
            log.warn("生命周期对账①：{} 条任务子任务全终态但未收尾，已重放收尾", fixed);
        }
        return fixed;
    }

    @Override
    public int reconcileDerivations(int limit) {
        List<Task> derivations = taskMapper.selectTerminalDerivationsAwaitingParent(limit);
        if (derivations == null || derivations.isEmpty()) {
            return 0;
        }
        int fixed = 0;
        for (Task derived : derivations) {
            try {
                if (propagateDerivedCompletion(derived, "reconcile")) {
                    fixed++;
                }
            } catch (Exception e) {
                log.error("生命周期对账②派生传播失败: derivedTaskId={}", derived.getId(), e);
            }
        }
        if (fixed > 0) {
            log.warn("生命周期对账②：{} 条派生任务终态但父 SubTask 仍等派生，已重放传播", fixed);
        }
        return fixed;
    }

    @Override
    public int reconcileMissingFinalReports(int limit) {
        if (!dispatchProperties.isAutoFinalReportEnabled()) {
            return 0;
        }
        List<Long> taskIds = taskMapper.selectDoneTasksMissingFinalReport(limit);
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        int triggered = 0;
        for (Long taskId : taskIds) {
            try {
                taskFinalReportService.onTaskAutoCompleted(new TaskAutoCompletedEvent(taskId));
                triggered++;
                recordReconciled(taskId, null, "missing_final_report");
            } catch (Exception e) {
                log.error("生命周期对账③报告补生成失败: taskId={}", taskId, e);
            }
        }
        if (triggered > 0) {
            log.warn("生命周期对账③：{} 条 DONE 任务缺整合报告，已触发补生成", triggered);
        }
        return triggered;
    }

    private void recordReconciled(Long taskId, Long subTaskId, String check) {
        try {
            taskTimelineService.recordEvent(taskId, subTaskId, TimelineEventCode.TASK_LIFECYCLE_RECONCILED.code(),
                    AgentRole.SYSTEM, null, Map.of("check", check));
        } catch (Exception e) {
            log.debug("生命周期对账时间线写入失败: taskId={}, check={}, err={}", taskId, check, e.getMessage());
        }
    }
}
