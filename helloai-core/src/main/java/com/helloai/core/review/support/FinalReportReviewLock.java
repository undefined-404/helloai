package com.helloai.core.review.support;

/**
 * 最终报告审查「防双审互斥锁」的键与租期常量（§12.2 审查链三级容错）。
 *
 * <p>L1（AFTER_COMMIT 事件）/ L2（Outbox→MQ）/ L3（孤儿巡检）三路可能对同一任务并发触发审查，
 * 用 Redis 锁 {@code final_report_review:{taskId}} 保证「审查 LLM 判定 + 驳回落库」窗口内仅一路进入。
 * 本类是该锁键与租期的<b>唯一事实源</b>：{@code FinalReportReviewServiceImpl}（L1/L2 审查体）与
 * {@code FinalReportReviewOrphanTask}（L3 孤儿收敛）共用，杜绝两处各写一份字符串/秒数导致漂移。</p>
 *
 * <p><b>为什么 L3 孤儿收敛也要抢这把锁</b>：孤儿巡检以 DB 状态为事实源，若某条审查正在进行
 * （持锁，驳回落返工重写可达 {@code TTL_SECONDS} 秒），巡检不抢锁就把 REVIEWING 收敛 DONE，
 * 会与在途审查互相覆盖——迟到的 reject 触发 rework 把已收敛的 DONE 又打回 GENERATING。
 * 抢同一把锁后：锁被持有 = 审查在途 = 非真孤儿，跳过即可。</p>
 *
 * <p><b>租期与巡检阈值的关系</b>：L3 孤儿巡检阈值
 * （{@code helloai.dispatch.final-report-review-orphan-threshold-seconds}）必须 ≥ {@link #TTL_SECONDS}，
 * 否则慢审查持锁期间会被巡检误判为孤儿。</p>
 */
public final class FinalReportReviewLock {

    /** 防双审互斥锁键前缀（完整键 = 前缀 + taskId）。 */
    public static final String KEY_PREFIX = "final_report_review:";

    /**
     * 锁租期（秒）：覆盖「审查 LLM 判定 + 可能的驳回落库」窗口。驳回返工会同步触发一次完整
     * 重写（出纲 + 正文，可达数分钟），故取 600s；显式 leaseTime 禁用看门狗，崩溃残留由 TTL
     * 自动释放（即使提前释放，状态守卫 + 陈旧守卫仍是第二道防线）。
     */
    public static final long TTL_SECONDS = 600L;

    private FinalReportReviewLock() {
    }

    /** 拼接某任务的防双审锁完整键。 */
    public static String key(Long taskId) {
        return KEY_PREFIX + taskId;
    }
}
