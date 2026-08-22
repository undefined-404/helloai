package com.helloai.job.task;

import com.helloai.common.config.ReviewProperties;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.task.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Reviewer 抽检任务（反馈回路 Phase 4）：对窗口内 APPROVED 审查记录抽样复审。
 *
 * <p>只度量不改状态：复审结果写 review_recheck_log（discrepancy=原 APPROVED 复审
 * REJECTED 的放水标记）+ timeline 观测，子任务状态与 review_record 均不动；
 * Reviewer 维度画像计数由 {@link SubTaskReviewService#recheckReviewRecord} 内部
 * best-effort 增量。窗口/抽样比例/批量上限走 {@code helloai.review.*} 配置。</p>
 *
 * <p>保护机制（与 {@link PlanningTimeoutTask} 同构）：
 * <ul>
 *   <li>Redis 分布式锁（token + Lua 安全解锁）保证多实例单轮仅一个实例执行</li>
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
    private final SubTaskReviewService subTaskReviewService;
    private final ReviewProperties reviewProperties;
    private final StringRedisTemplate redis;

    private static final String LOCK_KEY = "scheduler:lock:ReviewerRecheck";

    /**
     * 安全释放脚本：仅当 Redis 中锁的 value 仍等于本实例的 token 时才删除，
     * 避免本实例因超时丢锁后误删新持有者锁的并发窗口。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** Redis 锁 TTL（秒）：大于单轮最坏处理时间（LLM 复审调用窗口）。 */
    private static final long LOCK_TTL_SECONDS = 300;

    @Scheduled(fixedDelayString = "${helloai.review.recheck-interval-ms:3600000}")
    public void recheck() {
        if (!reviewProperties.isRecheckEnabled()) {
            return;
        }
        // tryLock 时生成唯一 token；unlock 必须用同一 token，避免误删他人锁
        String token = UUID.randomUUID().toString();
        if (!tryLock(token)) {
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
                    subTaskReviewService.recheckReviewRecord(reviewRecordId);
                    done++;
                } catch (Exception e) {
                    failed++;
                    log.error("Reviewer抽检复审失败: reviewRecordId={}", reviewRecordId, e);
                }
            }
            log.info("Reviewer抽检完成: 抽样={}, 完成={}, 失败={}", ids.size(), done, failed);
        } catch (Exception e) {
            log.error("ReviewerRecheckTask 执行异常", e);
        } finally {
            unlock(token);
        }
    }

    private boolean tryLock(String token) {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock(String token) {
        // Lua 脚本：仅当 Redis 中的 value 等于本实例的 token 时才删除，
        // 避免本实例因超时丢锁后误删新持有者的锁。
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY), token);
        } catch (Exception e) {
            // 释放失败不阻断业务，下次定时任务会重新竞争锁；仅记录
            log.warn("释放 Redis 锁失败: lockKey={}, token={}", LOCK_KEY, token, e);
        }
    }
}
