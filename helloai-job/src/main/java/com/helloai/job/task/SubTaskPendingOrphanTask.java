package com.helloai.job.task;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
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
 * PENDING 孤儿子任务巡检任务（v2.6 §4.1 2026-07-20 新增）。
 *
 * <p>填补可靠性缺口：dispatch-mode=EVENT 主路径上 Spring 事务事件丢失、
 * {@code agent_execution_record} 行未被创建、但 {@code sub_task} 一直停在 PENDING。
 * 现有 {@link ExecutionCommandPoller} 扫描的是 {@code status='PENDING' AND
 * status='PENDING' AND last_attempt_at < cutoff} —— 针对的"已有 execution_record
 * 但积压未消费"的孤儿，覆盖不到"压根没建 record"的情况。本任务补上这个间隙。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *     <li>每 60s 扫一次：{@code status='PENDING' AND create_time &lt; now - 30min
 *         AND NOT EXISTS agent_execution_record}</li>
 *     <li>逐条重新触发 {@link SubTaskDispatchService#dispatchPendingSubTaskAuto}：
 *         PENDING 状态的子任务自动按角色选人并进入弹性调度链</li>
 *     <li>Redis 锁保证多实例串行，单批上限 50 条防阻塞</li>
 * </ul>
 *
 * <h3>与现有任务边界</h3>
 * <ul>
 *     <li>{@link AssignedSubTaskTimeoutTask}：处理 ASSIGNED 超时未 claim</li>
 *     <li>{@link ExecutionCompensationTask}：处理已有 execution_record 但 PENDING/RUNNING
 *         超时</li>
 *     <li>{@code SubTaskPendingOrphanTask（本类）}：处理无 execution_record 的 PENDING 孤儿</li>
 *     <li>三条职责互不重叠，各管一片</li>
 * </ul>
 *
 * <h3>幂等保护</h3>
 * <ol>
 *     <li>每条孤儿重新触发调度时先按 id 查最新状态，避免回读到陈旧 PENDING 状态</li>
 *     <li>{@code dispatchPendingSubTaskAuto} 本身要求子任务当前为 PENDING 状态，
 *         若已被外部 Agent claim 或被其它路径推进到非 PENDING，会抛 BizException；
 *         catch 后仅记录日志，不影响同轮其他记录</li>
 * </ol>
 *
 * <h3>配置项</h3>
 * <ul>
 *     <li>{@code helloai.execution.pending-orphan-enabled}（默认 true）</li>
 *     <li>{@code helloai.execution.pending-orphan-scan-interval-ms}（默认 60000）</li>
 *     <li>{@code helloai.execution.pending-orphan-threshold-minutes}（默认 30）</li>
 *     <li>{@code helloai.execution.pending-orphan-batch-size}（默认 50）</li>
 * </ul>
 *
 * @see AssignedSubTaskTimeoutTask
 * @see ExecutionCompensationTask
 * @see ExecutionCommandPoller
 * @see SubTaskDispatchService#dispatchPendingSubTaskAuto
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskPendingOrphanTask {

    private final SubTaskMapper subTaskMapper;
    private final SubTaskService subTaskService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final AgentExecutionProperties executionProperties;
    private final StringRedisTemplate redis;

    private static final String LOCK_KEY = "scheduler:lock:SubTaskPendingOrphan";

    /**
     * 安全释放脚本：仅当 Redis 中锁的 value 仍等于本实例的 token 时才删除，
     * 避免本实例因 scan 超时而被锁过期 → 被其他实例拿到锁 → 本实例 finally
     * 中误删新持有者锁的并发窗口。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** Redis 锁 TTL（秒）：大于单轮最坏处理时间 */
    private static final long LOCK_TTL_SECONDS = 60;

    /**
     * 周期扫描入口。
     *
     * <p>{@code fixedDelayString} 直接绑定配置项，支持通过 yaml / 环境变量动态调整。
     * 默认 60 秒一跑；每条孤儿由 {@link SubTaskDispatchService#dispatchPendingSubTaskAuto}
     * 统一进入选人 → ASSIGNED 链。</p>
     */
    @Scheduled(fixedDelayString = "${helloai.execution.pending-orphan-scan-interval-ms:60000}")
    public void scan() {
        if (!executionProperties.isPendingOrphanEnabled()) {
            return;
        }
        // tryLock 时生成唯一 token；unlock 必须用同一 token，避免误删他人锁
        String token = UUID.randomUUID().toString();
        if (!tryLock(token)) {
            log.debug("SubTaskPendingOrphanTask 跳过（其他实例正在执行）");
            return;
        }

        try {
            int thresholdMinutes = executionProperties.getPendingOrphanThresholdMinutes();
            int batchSize = executionProperties.getPendingOrphanBatchSize();
            OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(thresholdMinutes);

            List<Long> orphanIds = subTaskMapper.selectStalePendingWithoutExecutionRecord(cutoff, batchSize);

            if (orphanIds.isEmpty()) {
                return;
            }

            log.warn("PENDING 孤儿巡检: 发现 {} 个无 execution_record 的 PENDING 子任务 (threshold={}min)",
                    orphanIds.size(), thresholdMinutes);

            int recovered = 0;
            int failed = 0;
            int skipStatusChanged = 0;
            int skipNotReady = 0;

            for (Long subTaskId : orphanIds) {
                try {
                    // 防御性回读：避免回读到陈旧 PENDING 状态（某条可能刚被其它路径推进）
                    SubTask latest = subTaskService.getById(subTaskId);
                    if (latest == null) {
                        log.debug("跳过：子任务不存在: subTaskId={}", subTaskId);
                        continue;
                    }
                    if (latest.getStatus() != com.helloai.common.constant.SubTaskStatus.PENDING) {
                        // 已被其它路径推进（claim/submit/block），自然跳过
                        skipStatusChanged++;
                        log.debug("跳过：状态已变更: subTaskId={}, currentStatus={}",
                                subTaskId, latest.getStatus());
                        continue;
                    }
                    // V27: 依赖未就绪的节点不触发分发（保持 PENDING 等上游 DONE 后解锁），
                    // 避免孤儿扫描误伤依赖编排中的合法阻塞节点
                    if (!subTaskService.isReady(latest)) {
                        skipNotReady++;
                        log.debug("跳过：依赖未就绪: subTaskId={}, dependsOn={}",
                                subTaskId, latest.dependsOnIdList());
                        continue;
                    }

                    // PENDING 孤儿重派：默认按 EXECUTOR 角色选人
                    // —— 因为 PENDING 子任务通常还未指定角色，Module.role 在更上层传入；
                    // 本任务作为兜底路径，统一按 EXECUTOR 处理最常见场景
                    subTaskDispatchService.dispatchPendingSubTaskAuto(subTaskId, AgentRole.EXECUTOR);
                    recovered++;
                    log.info("PENDING 孤儿已重派: subTaskId={}", subTaskId);
                } catch (BizException bizEx) {
                    // 子任务状态被外部改写（典型 case：刚被另外路径 claim / block / cancel）
                    // —— 视为并发冲突，skip 而不是 fail
                    skipStatusChanged++;
                    log.info("PENDING 孤儿跳过（状态冲突）: subTaskId={}, reason={}",
                            subTaskId, bizEx.getMessage());
                } catch (Exception e) {
                    failed++;
                    log.error("PENDING 孤儿重派失败: subTaskId={}", subTaskId, e);
                }
            }

            if (recovered > 0 || failed > 0 || skipStatusChanged > 0 || skipNotReady > 0) {
                log.info("PENDING 孤儿巡检完成: 扫描={}, 重派={}, 跳过（状态冲突）={}, 跳过（依赖未就绪）={}, 失败={}",
                        orphanIds.size(), recovered, skipStatusChanged, skipNotReady, failed);
            }

        } catch (Exception e) {
            log.error("SubTaskPendingOrphanTask 执行异常", e);
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
