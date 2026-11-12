package com.helloai.common.constant;

/**
 * 任务主状态。
 *
 * <p><b>{@code PLANNING} 与 {@code AWAITING_PLAN_CONFIRM} 必须分开</b>（2026-11-08）：
 * 改前两者共用 {@code PLANNING}，同一字段同时表达「机器正在拆解（应超时回收）」与
 * 「草案等用户确认（可以等一周）」两种相反的生命周期。所有判定只能靠
 * {@code NOT EXISTS (草案)} 打补丁，而补丁的假设（有草案 = 拆解成功）在「回收发生在
 * 草案落库之前」时不成立 ⇒ 误杀正在跑的拆解，产生「草案在库 + task=PENDING」的死结
 * （confirm 与 re-decompose 双向拒绝，只能改库）。</p>
 *
 * <p>拆分后 {@code PLANNING} 纯化为「拆解中」单一含义，超时判活因此只依据拆解租约
 * （{@code task.decompose_lease_until}），不再需要看子任务表。</p>
 */
public enum TaskStatus {
    PENDING,

    /** 拆解中：Planner 正在调模型/落库，由拆解租约判活，超时由巡检回收。 */
    PLANNING,

    /**
     * 草案待确认：拆解已成功、草案已写入暂存区（{@code task_plan_draft}），等用户确认或拒绝。
     *
     * <p><b>不参与超时回收</b>——这里的等待时长取决于人，不是故障。也不要因为等待久
     * 就作废草案：用户可能已经改了一半。</p>
     */
    AWAITING_PLAN_CONFIRM,

    IN_PROGRESS,
    DONE,
    CANCELLED
}
