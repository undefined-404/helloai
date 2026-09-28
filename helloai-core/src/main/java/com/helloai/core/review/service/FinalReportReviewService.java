package com.helloai.core.review.service;

import com.helloai.core.shared.event.TaskFinalReportGeneratedEvent;

import java.time.OffsetDateTime;

/**
 * 最终整合报告质量审查服务（3A 核验/返工闭环）。
 *
 * <p>审查链三级容错（§12.2）的<b>共同执行体</b>——三条触发路径复用同一入口
 * {@link #review(Long, OffsetDateTime, int, int)}，由内部的 Redis 防双审锁 +
 * 报告状态守卫保证「先到者执行、后到者幂等跳过」：</p>
 * <ul>
 *   <li><b>L1</b>：{@link #onFinalReportGenerated} —— {@code @TransactionalEventListener(AFTER_COMMIT)}
 *       承接报告写回事务提交后的事件，提交到报告审查专用线程池执行（不阻塞发布线程）；</li>
 *   <li><b>L2</b>：{@code MqFinalReportReviewConsumer} 消费报告审查队列
 *       （Outbox 持久化后由 {@code AgentEventCompensationTask} 投递，重启不丢）；</li>
 *   <li><b>L3</b>：{@code FinalReportReviewOrphanTask} 扫 DB 超时 {@code REVIEWING} 兜底
 *       （当前策略为直接收敛 DONE，不再重复烧 LLM）。</li>
 * </ul>
 *
 * <p>审查失败/异常只影响质量闭环，不影响任务 DONE 与报告已落库事实——
 * 报告是增值物，不是交付门槛。</p>
 */
public interface FinalReportReviewService {

    /**
     * L1 触发：报告生成事件（AFTER_COMMIT）→ 提交专用池执行审查。
     *
     * <p>只做<b>提交</b>不阻塞发布线程；线程池饱和时落
     * {@code task_final_report_review_skipped(executor_saturated)} 并收敛 DONE。</p>
     */
    void onFinalReportGenerated(TaskFinalReportGeneratedEvent event);

    /**
     * 执行一次报告质量审查（L1/L2 共用入口，幂等：非 REVIEWING 直接跳过）。
     *
     * @param taskId       任务 ID
     * @param reportTime   报告写回时间（陈旧守卫锚点）
     * @param attempt      生成轮次（决定是否触发返工重写）
     * @param reportLength 报告正文字符数（审查 Prompt 注入）
     */
    void review(Long taskId, OffsetDateTime reportTime, int attempt, int reportLength);
}
