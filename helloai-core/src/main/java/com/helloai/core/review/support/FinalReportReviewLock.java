package com.helloai.core.review.support;

/**
 * 最终报告审查「防双审互斥锁」的键与租期常量（§12.2 审查链三级容错）。
 *
 * <p>L1（AFTER_COMMIT）/L2（Outbox→MQ）/L3（孤儿巡检）三路可能并发触发同一任务审查，用 Redis 锁
 * {@code final_report_review:{taskId}} 保证「LLM 判定 + 驳回落库」窗口仅一路进入。本类是锁键与租期的<b>唯一事实源</b>，
 * 审查体与 {@code FinalReportReviewOrphanTask} 共用，杜绝两处各写一份导致漂移。</p>
 *
 * <p>L3 孤儿收敛也抢这把锁：锁被持有 = 审查在途 = 非真孤儿，跳过即可，否则巡检把 REVIEWING 收敛 DONE 会与在途审查互相覆盖。
 * 巡检阈值（{@code helloai.dispatch.final-report-review-orphan-threshold-seconds}）必须 ≥ {@link #TTL_SECONDS}，否则慢审查持锁期间被误判孤儿。</p>
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
