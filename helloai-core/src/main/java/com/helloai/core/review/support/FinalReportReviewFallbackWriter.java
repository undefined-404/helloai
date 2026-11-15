package com.helloai.core.review.support;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TimelineEventCode;
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
 * <p>{@code onFinalReportGenerated} 是 {@code @TransactionalEventListener(AFTER_COMMIT)}，在报告事务已提交后于发布线程同步执行；
 * 该线程仍绑着已提交事务的资源，兜底写入（{@code convergeToDone}、{@code review_skipped} 审计）若沿用默认传播会加入这个不再提交的事务而丢失
 * （{@code recordEvent} REQUIRED 加入已提交事务；{@code convergeFinalReportToDone} 复用 autoCommit=false 连接无人 commit）。</p>
 *
 * <p>故下沉到本 Bean 的 {@code REQUIRES_NEW} 方法：无论从 AFTER_COMMIT 发布线程还是审查池线程调用都开新独立事务并确定提交
 * （同 {@code ExternalAgentFailureTracker} 模式）。异步审查体 {@code doReview} 的收敛/跳过出口同样经本 Bean。</p>
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
                TimelineEventCode.TASK_FINAL_REPORT_REVIEW_SKIPPED.code(), AgentRole.REVIEWER, null,
                Map.of("reason", reason, "attempt", attempt));
        taskService.convergeFinalReportToDone(taskId, reportTime);
    }
}
