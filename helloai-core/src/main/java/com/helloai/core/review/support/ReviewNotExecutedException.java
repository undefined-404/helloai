package com.helloai.core.review.support;

/**
 * 最终报告审查「未真正执行」信号（§12.5.5 #5）。
 *
 * <p>审查体进 {@code doReview} 前须抢防双审锁 {@code final_report_review:{taskId}}；锁被兄弟链路（L1/L2/L3）持有或不可用时本次未执行任何审查，
 * 抛本异常把「未执行」与「已执行但失败」严格区分：L1（{@code reviewQuietly}）捕获按正常跳过记 debug（重投靠 L2/L3）；
 * L2（{@code MqFinalReportReviewConsumer}）让异常上抛 → {@code markFailed} → {@code basicNack(requeue=false)} 进死信可重放。</p>
 *
 * <p>不能静默 return：否则外层 {@code tryConsume} 见 Runnable 不抛即 {@code markConsumed} 恒 ACK，eventId 被永久标记成功，
 * 持锁进程崩溃后再无链路重投，质量门被静默跳过。{@code doReview} 内部异常<b>不</b>包装为本异常（属「已开始执行」，就地吞掉避免重投重复烧 LLM）；
 * 只有「锁都没抢到、一行审查逻辑都没跑」才抛。</p>
 */
public class ReviewNotExecutedException extends RuntimeException {

    public ReviewNotExecutedException(String message) {
        super(message);
    }

    public ReviewNotExecutedException(String message, Throwable cause) {
        super(message, cause);
    }
}
