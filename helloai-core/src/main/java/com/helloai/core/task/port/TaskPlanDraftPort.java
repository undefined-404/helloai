package com.helloai.core.task.port;

/**
 * 任务域读取「本次拆解是否已产出暂存区草案」的反转端口（`AD-5b`）。
 *
 * <p>草案只落 `task_plan_draft` 后，task 域不能跨域直读暂存区；
 * 由 planner 域实现本端口，供拆解超时回收决定 `AWAITING_PLAN_CONFIRM` 还是 `PENDING`。</p>
 */
public interface TaskPlanDraftPort {

    /** 该任务是否存在当前有效（未作废/未确认）的暂存区草案。 */
    boolean hasActiveDrafts(Long taskId);
}
