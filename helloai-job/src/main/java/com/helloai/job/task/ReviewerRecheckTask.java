package com.helloai.job.task;

import com.helloai.common.config.ReviewProperties;
import com.helloai.core.review.support.ReviewRecheckExecutor;
import com.helloai.core.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Reviewer 抽检任务（反馈回路 Phase 4）：对窗口内 APPROVED 审查记录抽样复审。
 *
 * <p>只度量不改状态：复审结果写 review_recheck_log（discrepancy=原 APPROVED 复审
 * REJECTED 的放水标记）+ timeline 观测，子任务状态与 review_record 均不动；
 * Reviewer 维度画像计数由 {@link ReviewRecheckExecutor#recheckReviewRecord} 内部
 * best-effort 增量。窗口/抽样比例/批量上限走 {@code helloai.review.*} 配置。</p>
 *
 * <p>保护机制（与 {@link PlanningTimeoutTask} 同构）：
 * <ul>
 *   <li>ShedLock 实例级互斥（@SchedulerLock，Redis 存储锁记录）保证多实例单轮仅一个实例执行</li>
 *   <li>抽样批量 = 候选数 × 比例折算，上限 recheck-max-batch 防单轮 LLM 调用过多</li>
 *   <li>每条失败只记日志不抛异常，不阻塞同轮其它记录</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewerRecheckTask {

    private final ReviewService reviewService;
    private final ReviewRecheckExecutor reviewRecheckExecutor;
    private final ReviewProperties reviewProperties;

    @Scheduled(fixedDelayString = "${helloai.review.recheck-interval-ms:3600000}")
    @SchedulerLock(name = "reviewerRecheck", lockAtMostFor = "PT300S")
    public void recheck() {
        if (!reviewProperties.isRecheckEnabled()) {
            return;
        }
        try {
            int windowDays = reviewProperties.getRecheckWindowDays() > 0
                    ? reviewProperties.getRecheckWindowDays() : 7;
            OffsetDateTime since = OffsetDateTime.now().minusDays(windowDays);
            long candidates = reviewService.countRecheckCandidates(since);
            if (candidates <= 0) {
                return;
            }
            // 抽样批量：候选数 × 比例折算，下限 1、上限 recheck-max-batch
            long wanted = Math.max(1L, (long) Math.ceil(candidates * reviewProperties.getRecheckSampleRatio()));
            int batch = (int) Math.min(reviewProperties.getRecheckMaxBatch(), wanted);
            List<Long> ids = reviewService.listRecheckCandidateIds(since, batch);
            if (ids.isEmpty()) {
                return;
            }
            log.info("Reviewer抽检: 候选={}, 本轮抽样={}, windowDays={}", candidates, ids.size(), windowDays);
            int done = 0;
            int failed = 0;
            for (Long reviewRecordId : ids) {
                try {
                    reviewRecheckExecutor.recheckReviewRecord(reviewRecordId);
                    done++;
                } catch (Exception e) {
                    failed++;
                    log.error("Reviewer抽检复审失败: reviewRecordId={}", reviewRecordId, e);
                }
            }
            log.info("Reviewer抽检完成: 抽样={}, 完成={}, 失败={}", ids.size(), done, failed);
        } catch (Exception e) {
            log.error("ReviewerRecheckTask 执行异常", e);
        }
    }
}
