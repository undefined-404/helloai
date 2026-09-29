package com.helloai.core.review.support;

/**
 * 最终报告审查「未真正执行」信号（§12.5.5 #5）。
 *
 * <p>审查体在进入 {@code doReview} 之前需要先抢到防双审锁
 * （{@code final_report_review:{taskId}}）。当锁被兄弟链路（L1/L2/L3 之一）持有、
 * 或锁不可用（Redis 异常 / 获取被中断）时，本次调用<b>根本没有执行任何审查</b>，
 * 此时抛出本异常，用于把「未执行」与「已执行但失败」两种语义严格区分开：</p>
 *
 * <ul>
 *   <li><b>L1（{@code reviewQuietly}）</b>：捕获本异常按<b>正常跳过</b>记 debug——
 *       L1 是尽力而为的内存触发，重投/兜底由 L2/L3 负责，无需在 L1 侧重试；</li>
 *   <li><b>L2（{@code review} ← {@code MqFinalReportReviewConsumer}）</b>：让本异常<b>向上传播</b>，
 *       由 {@code AbstractIdempotentConsumer.tryConsume} 走 {@code markFailed} + 抛出，
 *       最终 {@code basicNack(requeue=false)} 进死信台账可重放。</li>
 * </ul>
 *
 * <p><b>为什么不能静默 return</b>：修复前 L2 抢锁失败仅 {@code log.debug}+{@code return}，
 * 外层 {@code tryConsume} 只要 Runnable 不抛就 {@code markConsumed} → 恒 {@code basicAck}，
 * eventId 被<b>永久标记为已成功消费</b>；此后若持有锁的 L1 进程崩溃，则再无任何链路重投，
 * 报告只能等 L3 孤儿巡检收敛 DONE（<b>不审查</b>），质量门被静默跳过。抛出本异常即恢复
 * 「未执行 → 判失败 → 可重投」的正确语义。</p>
 *
 * <p><b>注意</b>：{@code doReview} 内部（陈旧守卫 / 状态守卫 / LLM 调用 / 判定）的异常
 * <b>不</b>包装为本异常——那些属于「已开始执行」，遵循「审查失败不影响报告交付」哲学就地吞掉，
 * 由 L2 视为已消费 ACK，避免重投重复烧 LLM。只有「锁都没抢到、一行审查逻辑都没跑」才抛本异常。</p>
 */
public class ReviewNotExecutedException extends RuntimeException {

    public ReviewNotExecutedException(String message) {
        super(message);
    }

    public ReviewNotExecutedException(String message, Throwable cause) {
        super(message, cause);
    }
}
