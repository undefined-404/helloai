package com.helloai.core.task.service;

import com.helloai.core.task.entity.Task;

/**
 * 任务生命周期收尾与派生完成态传播（`ADR-008` §5）。
 *
 * <p>从 {@code SubTaskCompletionListener} 抽出的唯一收尾入口：监听器负责「在什么时机调」，
 * 本服务负责「怎么收尾、怎么把完成态传回父 SubTask」。抽出的动机是多一个调用方——
 * 定时对账任务必须能重放同一套动作，不能在 job 里复制一份判据（判据 4：同一事实不留两套）。</p>
 */
public interface TaskLifecycleService {

    /**
     * 全部有效子任务均 `DONE` / `CANCELLED` 时幂等推进 Task → `DONE`。
     *
     * <p>CAS 赢家唯一；CAS 成功后同步做派生完成态传播（`Y` → 父 SubTask），再发布
     * {@code TaskAutoCompletedEvent} 触发报告生成。任何异常不外抛——收尾是兜底动作，
     * 失败交给 {@code TaskLifecycleReconcileTask} 重放。</p>
     *
     * @return true = 本次调用完成了收尾（CAS 命中）
     */
    boolean tryCloseTask(Long taskId);

    /**
     * 派生 Task 已终态 → 按 `ADR-008` 把父 SubTask 推进到 `DONE`（有产出）或
     * `RESTRUCTURING`（失败/废除，转人工接管），并在父任务上记录时间线。
     *
     * <p>幂等：父 SubTask 已不在 `AWAITING_DERIVED` 时直接返回 false；
     * 并发下 `changeStatus` 的状态机 + 乐观锁保证只有一次生效。</p>
     *
     * @param derivedTask 派生 Task（需带 `parent_sub_task_id`）
     * @param trigger     触发来源（sync / reconcile），写进时间线便于区分主路径与兜底
     * @return true = 本次调用推进了父状态
     */
    boolean propagateDerivedCompletion(Task derivedTask, String trigger);

    /** 对账①：非终态任务但有效子任务全终态 → 重放 {@link #tryCloseTask(Long)}。返回修正条数。 */
    int reconcileSettledTasks(int limit);

    /** 对账②：派生 Task 终态但父 SubTask 仍等派生 → 重放传播。返回修正条数。 */
    int reconcileDerivations(int limit);

    /** 对账③：`DONE` 但整合报告缺失 → 触发报告生成。返回触发条数。 */
    int reconcileMissingFinalReports(int limit);
}
