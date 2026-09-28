package com.helloai.core.shared.event;

import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * 任务最终整合报告生成完成事件（报告质量审查触发源）。
 *
 * <p>由 {@code TaskFinalReportServiceImpl} 在报告写回成功后发布（{@code ApplicationEventPublisher}）；
 * 消费方 {@code FinalReportReviewListener} 以普通 {@code @EventListener} 承接（本发布点与
 * {@link TaskAutoCompletedEvent} 同款：无事务上下文，不能用 {@code @TransactionalEventListener}）。
 * 审查失败/驳回仅影响报告质量闭环，不影响任务 DONE 与报告已落库事实。</p>
 *
 * <p>§12.2 审查异步化：事件携带 {@link #reportTime}（= 写回时的 {@code final_report_time}，微秒截断），
 * 审查链据此做<b>陈旧守卫</b>——审查前 / rework 前 / 收敛置 DONE 时校验库中 {@code final_report_time}
 * 未变，变了说明期间已被新生成/回滚接管，旧链丢弃不覆盖新链状态。</p>
 */
@Getter
public class TaskFinalReportGeneratedEvent {

    private final Long taskId;
    /** 报告正文长度（字符）。 */
    private final int reportLength;
    /** 参与的 DONE 子任务产出数（章节分片输入规模）。 */
    private final int sectionCount;
    /** 生成轮次：首次生成=1，rework 递增（内存态，进程重启归零，仅作事件度量与返工上限判断）。 */
    private final int attempt;
    /** 报告写回时间戳（微秒截断与 TIMESTAMPTZ 精度对齐；陈旧守卫锚点）。 */
    private final OffsetDateTime reportTime;

    public TaskFinalReportGeneratedEvent(Long taskId, int reportLength, int sectionCount, int attempt,
                                         OffsetDateTime reportTime) {
        this.taskId = taskId;
        this.reportLength = reportLength;
        this.sectionCount = sectionCount;
        this.attempt = attempt;
        this.reportTime = reportTime;
    }
}