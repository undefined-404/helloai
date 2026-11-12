package com.helloai.core.planner.service;

/**
 * 拆解尝试派发器（`TD-16b`）：把「登记了但没人干」和「干到一半失联了」的尝试重新推进。
 *
 * <p><b>它补的是哪条缝</b>：`planById` 是「CAS 改状态」+「派 @Async」两步。进程崩在中间，
 * 任务卡在 {@code PLANNING} 但没有任何执行者，只能等分钟级超时巡检回退 {@code PENDING}
 * 让用户手动重拆。本派发器把「不丢」的保证从「靠进程活着」换成「靠库里有记录」。</p>
 *
 * <p><b>与 {@code PlanningTimeoutTask} 的分工</b>（两者都在 30s 周期跑，必须不打架）：</p>
 * <ul>
 *   <li>{@code PlanningTimeoutTask}：任务级 —— 「{@code task} 的租约过期」⇒ 回退 {@code PENDING}，
 *       等用户重新触发。它是**业务层**的超时处置。</li>
 *   <li>本派发器：尝试级 —— 「{@code attempt} 的租约过期」⇒ 判悬挂、{@code attempt_no + 1}、
 *       自动重跑。它是**可靠性层**的自愈。</li>
 * </ul>
 * <p>两者的先后不冲突：若 {@code PlanningTimeoutTask} 先把任务回退了，本派发器在重派前
 * 会发现「任务已不在 {@code PLANNING}」⇒ 把该尝试标 {@code FAILED_TERMINAL}
 * （说明「这次尝试的目标已不存在」），不会把已回退的任务又推回拆解中。</p>
 *
 * <p>实现在 core、调度在 helloai-job：这样判据性逻辑可单测，{@code @Scheduled} 与
 * ShedLock 只留在 job 模块（与 {@code PlanningTimeoutTask} 同构）。</p>
 */
public interface PlanAttemptDispatcher {

    /**
     * 执行一轮派发：回收悬挂 + 重派可重试 + 触发无人接手的 {@code PENDING}。
     *
     * <p>单轮内每条失败只记日志、不抛出——一条坏记录不该拦住同轮其它记录
     * （与 {@code PlanningTimeoutTask} 的处理口径一致）。</p>
     *
     * @param limit 单类扫描上限（防单轮阻塞调度）
     * @return 本轮各项计数，便于日志与观测
     */
    DispatchReport dispatchOnce(int limit);

    /**
     * 一轮派发的计数快照。
     *
     * @param reclaimedHanging 判悬挂并转可重试的条数
     * @param redispatched     重新触发执行的条数
     * @param abandoned        因目标已不存在（任务离开 {@code PLANNING}）而终止的条数
     * @param failed           本轮触发时抛异常的条数（已记日志，下轮再试）
     */
    record DispatchReport(int reclaimedHanging, int redispatched, int abandoned, int failed) {

        public boolean isEmpty() {
            return reclaimedHanging == 0 && redispatched == 0 && abandoned == 0 && failed == 0;
        }
    }
}
