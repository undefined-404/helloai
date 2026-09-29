package com.helloai.core.review.support;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 最终报告审查「兜底落库」独立事务写入器（§12.5.5 #4）。
 *
 * <p><b>为什么需要它</b>：{@code FinalReportReviewServiceImpl.onFinalReportGenerated} 是
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 监听器——它在报告写回事务<b>已提交</b>后、
 * 于<b>发布线程</b>上同步执行。此时旧事务的资源仍绑定在线程上但不会再被提交，两条同步兜底写入
 * （审查开关关闭时的 {@code convergeToDone}；审查线程池饱和时的 {@code review_skipped} 审计 +
 * {@code convergeToDone}）若沿用默认传播，会<b>加入这个已提交的事务</b>，Spring 之后不再提交它们：</p>
 * <ul>
 *   <li>{@code TaskTimelineService.recordEvent} 是 {@code REQUIRED} → 加入已提交事务 → 审计丢失；</li>
 *   <li>{@code TaskService.convergeFinalReportToDone} 无事务注解 → 复用已提交事务的连接
 *       （{@code autoCommit=false} 且无人再 commit）→ 收敛丢失。</li>
 * </ul>
 *
 * <p>后果：审查池饱和时 {@code review_skipped} 审计缺失 + 状态未收敛，报告停在 {@code REVIEWING}
 * 只能等 L3 孤儿巡检兜底（并附带 #3 的误导审计）。</p>
 *
 * <p><b>修法</b>：把这两条兜底写入下沉到本 Bean 的 {@code REQUIRES_NEW} 方法——无论从
 * AFTER_COMMIT 发布线程（有已提交的悬挂事务）还是从审查专用池线程（无事务）调用，都开启一个
 * 全新的独立事务并确定提交，兜底写入不再丢失。与 {@code ExternalAgentFailureTracker} 同款模式
 * （计数/审计侧写入用 {@code REQUIRES_NEW} 与主链路事务解耦）。异步审查体（{@code doReview}）
 * 内的 {@code convergeToDone} / {@code skipped} 出口同样经本 Bean，语义统一、行为不变。</p>
 *
 * @see com.helloai.core.review.service.impl.FinalReportReviewServiceImpl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinalReportReviewFallbackWriter {

    private final TaskService taskService;
    private final TaskTimelineService taskTimelineService;

    /**
     * 独立事务收敛 {@code REVIEWING → DONE}（{@code final_report_status} + {@code final_report_time}
     * 双条件 CAS，版本已被接管则 0 行放弃）。供 AFTER_COMMIT 兜底与异步审查体各收敛出口共用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void convergeToDone(Long taskId, OffsetDateTime reportTime) {
        taskService.convergeFinalReportToDone(taskId, reportTime);
    }

    /**
     * 独立事务落 {@code task_final_report_review_skipped} 审计并收敛 DONE——两步原子提交，
     * 避免「审计写了但收敛丢了」或反之。reason 区分无审查人 / 自审守卫 / 池满等跳过原因。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordSkippedAndConverge(Long taskId, OffsetDateTime reportTime, int attempt, String reason) {
        taskTimelineService.recordEvent(taskId, null,
                "task_final_report_review_skipped", AgentRole.REVIEWER, null,
                Map.of("reason", reason, "attempt", attempt));
        taskService.convergeFinalReportToDone(taskId, reportTime);
    }
}
