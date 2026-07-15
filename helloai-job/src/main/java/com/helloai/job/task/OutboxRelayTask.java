package com.helloai.job.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentCommandOutboxRelayProperties;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentCommandOutboxStatus;
import com.helloai.core.agent.command.ExecutionCommandMqPublisher;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.core.entity.AgentCommandOutboxEvent;
import com.helloai.core.service.AgentCommandOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Phase 2H ②a 引入：
 * 执行命令 Outbox Relay 周期任务。
 *
 * <p><b>职责</b>：扫描 {@code agent_command_outbox} 中 PENDING 的行，调用
 * {@link ExecutionCommandMqPublisher} 真正投递到 RabbitMQ，
 * 然后根据结果把行标记为 SENT / FAILED / 保留 PENDING(累计 retry_count)。</p>
 *
 * <p><b>触发频率</b>：默认 {@code fixedRate = 1000ms}（来自
 * {@link AgentCommandOutboxRelayProperties#getIntervalMs()}），单批上限
 * {@link AgentCommandOutboxRelayProperties#getBatchLimit()} 条。</p>
 *
 * <p><b>并发控制</b>：通过 Redis {@code SETNX 30s} 实现实例级互斥锁，
 * 避免多副本同时扫描同一批 outbox 行；本轮未引入乐观锁 CAS，
 * 因为 Relay 任务在写状态时使用 {@code WHERE status=PENDING} 的悲观条件更新
 * （{@link AgentCommandOutboxService#markSent} 等）保证状态不漂移。</p>
 *
 * <p><b>失败重试节奏</b>：单条 outbox 失败一次，{@code retry_count} + 1；
 * 下次可扫时间 {@code next_retry_at = now + baseBackoffSeconds * 2^retryCount}，
 * 由 {@link AgentCommandOutboxService#listReadyForRelay(int)} 的
 * {@code next_retry_at <= now} 条件自然回流。超过
 * {@link AgentCommandOutboxRelayProperties#getMaxRetry()} 次标记 FAILED 终态。</p>
 *
 * <p><b>启动条件</b>：本 Bean 由 {@link ConditionalOnProperty @ConditionalOnProperty}
 * 控制（{@code helloai.outbox.relay.enabled}，默认 true）。dispatch-mode 处于
 * MQ / BOTH 时启动期 {@code ExecutionDispatchValidator} 已 fail-fast 强制要求
 * {@code outbox.relay.enabled=true}。</p>
 *
 * <p><b>本轮明确不做</b>（②a 范围）：</p>
 * <ul>
 *   <li>publisher confirms / CorrelationData —— ②b 才引入 CONFIRMED 状态机；</li>
 *   <li>DLQ / 业务级告警 —— FAILED 终态仅记日志与 {@code error_msg}；</li>
 *   <li>Publisher 角色下沉（{@code OutboxCommandSender} 接口抽象）—— T2.4 后移到 ②b；</li>
 *   <li>并行批扫描 —— 本轮单线程顺序，最小闭环不需要。</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "helloai.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxRelayTask {

    private final AgentCommandOutboxService outboxService;
    private final ObjectProvider<ExecutionCommandMqPublisher> mqPublisherProvider;
    private final AgentCommandOutboxRelayProperties properties;
    private final AgentExecutionProperties executionProperties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private static final String LOCK_KEY = "scheduler:lock:AgentCommandOutbox";

    @Scheduled(fixedRateString = "${helloai.outbox.relay.interval-ms:1000}")
    public void relay() {
        if (!tryLock()) {
            return;
        }
        try {
            ExecutionCommandMqPublisher publisher = mqPublisherProvider.getIfAvailable();
            if (publisher == null) {
                // 启动期 ExecutionDispatchValidator 在 dispatch-mode ∈ {MQ,BOTH} 时已 fail-fast，
                // 这里仅是双保险：若运行时 Publisher 不可用（如 producer-enabled 被运行时切换），
                // 不要静默吞 outbox，至少要让日志可见。
                if (executionProperties.isDispatchMq()) {
                    log.error("OutboxRelay skipped: ExecutionCommandMqPublisher Bean unavailable "
                            + "while dispatch-mode requires MQ delivery. "
                            + "Check helloai.mq.execution-command.producer-enabled.");
                } else {
                    log.debug("OutboxRelay skipped: Publisher Bean not registered (dispatch-mode ≠ MQ/BOTH)");
                }
                return;
            }

            int batchLimit = properties.getBatchLimit();
            revertExpiredSent(batchLimit);
            List<AgentCommandOutboxEvent> ready = outboxService.listReadyForRelay(batchLimit);
            if (ready.isEmpty()) {
                return;
            }

            int sent = 0;
            int failed = 0;
            int finalFailed = 0;
            for (AgentCommandOutboxEvent row : ready) {
                RelayOutcome outcome = processOne(row, publisher);
                switch (outcome) {
                    case SENT -> sent++;
                    case FAILED -> failed++;
                    case FINAL_FAILED -> finalFailed++;
                    default -> {
                        // SKIPPED 不计入指标
                    }
                }
            }

            if (sent + failed + finalFailed > 0) {
                log.info("OutboxRelay batch done: scanned={}, sent={}, retry={}, final-failed={}",
                        ready.size(), sent, failed, finalFailed);
            }
        } finally {
            unlock();
        }
    }

    /**
     * 单条 outbox 行的处理：还原 command → 投递 → 标记结果。
     *
     * @return 处理结果（用于上层聚合日志）
     */
    private RelayOutcome processOne(AgentCommandOutboxEvent row, ExecutionCommandMqPublisher publisher) {
        if (row.getStatus() != AgentCommandOutboxStatus.PENDING) {
            return RelayOutcome.SKIPPED;
        }
        ExecutionCommand command;
        try {
            command = deserialize(row);
        } catch (Exception e) {
            // payload 损坏是终态错误，重试无意义，直接 FAILED
            log.error("OutboxRelay payload deserialize failed: outboxId={}, eventId={}",
                    row.getId(), row.getEventId(), e);
            outboxService.markFinalFailed(row.getId(),
                    "payload deserialize failed: " + e.getMessage(),
                    safeRetryCount(row));
            return RelayOutcome.FINAL_FAILED;
        }

        try {
            CorrelationData correlationData = publisher.publishWithCorrelation(command, String.valueOf(row.getId()));
            outboxService.markSent(row.getId(), OffsetDateTime.now());
            attachConfirmCallback(row, correlationData);
            log.info("OutboxRelay sent: outboxId={}, eventId={}, subTaskId={}, retryCount={}",
                    row.getId(), row.getEventId(), command.getSubTaskId(), row.getRetryCount());
            return RelayOutcome.SENT;
        } catch (Exception e) {
            int retryCount = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
            int maxRetry = properties.getMaxRetry();
            if (retryCount >= maxRetry) {
                outboxService.markFinalFailed(row.getId(), e.getMessage(), retryCount);
                log.error("OutboxRelay FINAL_FAILED (retryCount={} >= maxRetry={}): outboxId={}, eventId={}",
                        retryCount, maxRetry, row.getId(), row.getEventId(), e);
                return RelayOutcome.FINAL_FAILED;
            }
            OffsetDateTime nextRetryAt = OffsetDateTime.now().plusSeconds(
                    (long) properties.getBaseBackoffSeconds() * (1L << Math.min(retryCount, 10)));
            outboxService.markFailed(row.getId(), e.getMessage(), retryCount, nextRetryAt);
            log.warn("OutboxRelay retry scheduled: outboxId={}, eventId={}, retryCount={}, nextRetryAt={}",
                    row.getId(), row.getEventId(), retryCount, nextRetryAt, e);
            return RelayOutcome.FAILED;
        }
    }

    private void attachConfirmCallback(AgentCommandOutboxEvent row, CorrelationData correlationData) {
        if (correlationData == null) {
            return;
        }
        long timeoutSeconds = properties.getConfirmTimeoutSeconds();
        correlationData.getFuture()
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .whenComplete((confirm, ex) -> handleConfirm(row, confirm, ex, correlationData));
    }

    private void handleConfirm(AgentCommandOutboxEvent row,
                               CorrelationData.Confirm confirm,
                               Throwable ex,
                               CorrelationData correlationData) {
        if (row == null || row.getId() == null) {
            return;
        }
        Long outboxId = row.getId();
        String eventId = row.getEventId();
        if (ex != null) {
            scheduleRetryFromSent(outboxId, eventId, "confirm-timeout: " + ex.getMessage());
            return;
        }
        if (confirm == null) {
            scheduleRetryFromSent(outboxId, eventId, "confirm-null");
            return;
        }
        if (!confirm.isAck()) {
            scheduleRetryFromSent(outboxId, eventId, "confirm-nack: " + confirm.getReason());
            return;
        }
        if (correlationData.getReturned() != null) {
            scheduleRetryFromSent(outboxId, eventId, "returned: " + correlationData.getReturned().getReplyText());
            return;
        }
        outboxService.markConfirmed(outboxId, OffsetDateTime.now());
        log.info("OutboxRelay confirmed: outboxId={}, eventId={}", outboxId, eventId);
    }

    private void revertExpiredSent(int batchLimit) {
        List<AgentCommandOutboxEvent> expired = outboxService.listExpiredSentForRetry(batchLimit);
        if (expired.isEmpty()) {
            return;
        }
        for (AgentCommandOutboxEvent row : expired) {
            scheduleRetryFromSent(row.getId(), row.getEventId(), "confirm-timeout: expired-sent");
        }
    }

    private void scheduleRetryFromSent(Long outboxId, String eventId, String reason) {
        if (outboxId == null) {
            return;
        }
        int retryCount = 1;
        try {
            AgentCommandOutboxEvent current = outboxService.getById(outboxId);
            if (current != null && current.getRetryCount() != null) {
                retryCount = current.getRetryCount() + 1;
            }
        } catch (Exception ignored) {
        }

        int maxRetry = properties.getMaxRetry();
        if (retryCount >= maxRetry) {
            outboxService.markFinalFailedFromSent(outboxId, reason, retryCount);
            log.error("OutboxRelay FINAL_FAILED after confirm issue (retryCount={} >= maxRetry={}): outboxId={}, eventId={}, reason={}",
                    retryCount, maxRetry, outboxId, eventId, reason);
            return;
        }
        OffsetDateTime nextRetryAt = OffsetDateTime.now().plusSeconds(
                (long) properties.getBaseBackoffSeconds() * (1L << Math.min(retryCount, 10)));
        outboxService.markFailedFromSent(outboxId, reason, retryCount, nextRetryAt);
        log.warn("OutboxRelay retry scheduled from SENT: outboxId={}, eventId={}, retryCount={}, nextRetryAt={}, reason={}",
                outboxId, eventId, retryCount, nextRetryAt, reason);
    }

    /**
     * payload (jsonb → Map) → ExecutionCommandMqMessage → ExecutionCommand。
     *
     * <p>{@link ExecutionCommand} 是 {@code @Value} 不可变类；若 {@code recordId} 在
     * 序列化载体中缺失，用 {@code aggregate_id} 兜底，并通过 builder 重建。</p>
     */
    private ExecutionCommand deserialize(AgentCommandOutboxEvent row) {
        ExecutionCommandMqMessage mqMessage = objectMapper.convertValue(row.getPayload(), ExecutionCommandMqMessage.class);
        ExecutionCommand command = mqMessage.toDomain();
        if (command.getRecordId() == null && row.getAggregateId() != null) {
            try {
                Long fallbackRecordId = Long.parseLong(row.getAggregateId());
                command = ExecutionCommand.builder()
                        .recordId(fallbackRecordId)
                        .eventId(command.getEventId())
                        .subTaskId(command.getSubTaskId())
                        .agentId(command.getAgentId())
                        .trigger(command.getTrigger())
                        .accessType(command.getAccessType())
                        .build();
            } catch (NumberFormatException ignored) {
                // aggregateId 非数字（如未来 UUID 形态），保留 null
            }
        }
        return command;
    }

    private boolean tryLock() {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", 30, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock() {
        redis.delete(LOCK_KEY);
    }

    private int safeRetryCount(AgentCommandOutboxEvent row) {
        return row.getRetryCount() == null ? 0 : row.getRetryCount();
    }

    private enum RelayOutcome {
        SENT,
        FAILED,
        FINAL_FAILED,
        SKIPPED
    }
}
