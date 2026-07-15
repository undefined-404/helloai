package com.helloai.core.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.config.AgentCommandOutboxRelayProperties;
import com.helloai.common.constant.AgentCommandOutboxStatus;
import com.helloai.common.constant.OutboxAggregateType;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.core.entity.AgentCommandOutboxEvent;
import com.helloai.core.mapper.AgentCommandOutboxEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2H ②a 引入 / Phase 2H ②b 扩展：
 * 执行命令 Outbox（{@code agent_command_outbox}）的 Service。
 *
 * <p><b>职责边界</b>：本 Service 只承载"执行命令 → MQ"的投递生命周期；
 * 与 {@link AgentOutboxService}（SubTask 状态变更通知）严格分层——
 * 不共用表、不共用枚举、不共用 Service。</p>
 *
 * <p><b>方法清单</b>：</p>
 * <ol>
 *   <li>{@link #createPending}：业务事务内写入 PENDING 行，{@code eventId} 与
 *       {@link ExecutionCommand#getEventId()} 对齐作为唯一索引防重投；</li>
 *   <li>{@link #listReadyForRelay}：Relay 任务按批拉取"到时间且未超阈值的 PENDING"行；</li>
 *   <li>{@link #listExpiredSentForRetry}（②b 新增）：扫出 SENT 后超过 Confirm 超时窗口、仍未确认的行，
 *       应对重启后 in-flight future 丢失的恢复；</li>
 *   <li>{@link #markSent}（②b 收紧为二参）：发送成功标记 SENT 并写入 {@code last_sent_at}；</li>
 *   <li>{@link #markConfirmed}（②b 新增）：broker ACK 回写 CONFIRMED；</li>
 *   <li>{@link #markFailed}：发送失败累计 {@code retry_count} 并按指数退避设置 {@code next_retry_at}，保持 PENDING；</li>
 *   <li>{@link #markFailedFromSent}（②b 新增）：SENT → PENDING 回退，用于 NACK / return / confirm-timeout；</li>
 *   <li>{@link #markFinalFailed}：超过 {@code maxRetry} 标记 FAILED 终结，不再扫描；</li>
 *   <li>{@link #markFinalFailedFromSent}（②b 新增）：SENT → FAILED 终态，与 {@code markFailedFromSent} 共同覆盖
 *       "发送后失败"两路收尾。</li>
 * </ol>
 *
 * <p><b>本轮明确不做</b>（②b 收口后的遗留）：</p>
 * <ul>
 *   <li>Poller 降级为孤儿 / 超时 / 补偿兜底——T5 推进，本轮 Relay 仍是 MQ 主投递载体；</li>
 *   <li>{@code OutboxCompensationTask} 独立调度——本轮直接复用 {@code OutboxRelayTask}，
 *       不新增 Scheduled；</li>
 *   <li>DLQ 与 per-eventId 业务级熔断——本轮未引入，FAILED 仅写 {@code error_msg} 等待后续告警通道；</li>
 *   <li>CAS claim——本轮 Relay 单实例 Redis 锁串行执行，状态更新靠 {@code WHERE status=…} 悲观 CAS；
 *       后续若开多副本并发扫描再考虑乐观锁。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCommandOutboxService extends ServiceImpl<AgentCommandOutboxEventMapper, AgentCommandOutboxEvent> {

    private final AgentCommandOutboxRelayProperties relayProperties;

    /**
     * 在调用方事务内写入一条 PENDING outbox 行。
     *
     * <p><b>契约</b>：本方法不加 {@code @Transactional}，由调用方（{@code ExecutionCommandService}
     * 的"同事务写 ExecutionCommand + Outbox"路径）持有外层事务；
     * 入参 {@link ExecutionCommand#eventId} 必须非空，作为唯一索引防重投。</p>
     *
     * <p>聚合根 ID 字段语义：{@code aggregate_id = command.recordId}，
     * 与 {@code agent_execution_record.id} 对齐。</p>
     */
    public AgentCommandOutboxEvent createPending(ExecutionCommand command, ExecutionCommandMqMessage message) {
        if (command == null) {
            throw new IllegalArgumentException("ExecutionCommand must not be null when creating outbox row");
        }
        if (command.getEventId() == null || command.getEventId().isBlank()) {
            throw new IllegalArgumentException(
                    "ExecutionCommand.eventId must not be blank when creating outbox row");
        }
        AgentCommandOutboxEvent event = new AgentCommandOutboxEvent();
        event.setEventId(command.getEventId());
        // 固定 EXECUTION_COMMAND，避免后续统一 outbox 时语义发散
        event.setAggregateType(OutboxAggregateType.EXECUTION_COMMAND.code());
        event.setAggregateId(command.getRecordId() == null ? null : String.valueOf(command.getRecordId()));
        event.setPayload(toPayload(message));
        event.setStatus(AgentCommandOutboxStatus.PENDING);
        event.setRetryCount(0);
        save(event);
        log.info("AgentCommandOutbox PENDING row created: eventId={}, aggregateId={}",
                event.getEventId(), event.getAggregateId());
        return event;
    }

    /**
     * 拉取一批"到时间可重试、未超阈值"的 PENDING 行。
     *
     * <p>扫描条件：{@code status = PENDING} ∧ {@code next_retry_at IS NULL OR next_retry_at <= now}
     * ∧ {@code retry_count < maxRetry}。
     * 按 {@code create_time} 升序保证 FIFO；单批上限由调用方控制。</p>
     */
    public List<AgentCommandOutboxEvent> listReadyForRelay(int limit) {
        int maxRetry = relayProperties.getMaxRetry();
        return list(new LambdaQueryWrapper<AgentCommandOutboxEvent>()
                .eq(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.PENDING)
                .lt(AgentCommandOutboxEvent::getRetryCount, maxRetry)
                .and(w -> w.isNull(AgentCommandOutboxEvent::getNextRetryAt)
                        .or().le(AgentCommandOutboxEvent::getNextRetryAt, OffsetDateTime.now()))
                .orderByAsc(AgentCommandOutboxEvent::getCreateTime)
                .last("LIMIT " + limit));
    }

    public List<AgentCommandOutboxEvent> listExpiredSentForRetry(int limit) {
        int maxRetry = relayProperties.getMaxRetry();
        int confirmTimeoutSeconds = relayProperties.getConfirmTimeoutSeconds();
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(confirmTimeoutSeconds);
        return list(new LambdaQueryWrapper<AgentCommandOutboxEvent>()
                .eq(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.SENT)
                .isNull(AgentCommandOutboxEvent::getConfirmedAt)
                .isNotNull(AgentCommandOutboxEvent::getLastSentAt)
                .le(AgentCommandOutboxEvent::getLastSentAt, cutoff)
                .lt(AgentCommandOutboxEvent::getRetryCount, maxRetry)
                .orderByAsc(AgentCommandOutboxEvent::getLastSentAt)
                .last("LIMIT " + limit));
    }

    /**
     * 发送成功：标记 SENT，清空错误信息。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markSent(Long id, OffsetDateTime sentAt) {
        if (id == null) {
            return;
        }
        boolean ok = lambdaUpdate()
                .eq(AgentCommandOutboxEvent::getId, id)
                .eq(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.PENDING)
                .set(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.SENT)
                .set(AgentCommandOutboxEvent::getLastSentAt, sentAt)
                .set(AgentCommandOutboxEvent::getConfirmedAt, null)
                .set(AgentCommandOutboxEvent::getErrorMsg, null)
                .update();
        if (!ok) {
            log.warn("markSent skipped: outbox id={} not in PENDING (race / already processed)", id);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void markConfirmed(Long id, OffsetDateTime confirmedAt) {
        if (id == null) {
            return;
        }
        boolean ok = lambdaUpdate()
                .eq(AgentCommandOutboxEvent::getId, id)
                .eq(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.SENT)
                .set(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.CONFIRMED)
                .set(AgentCommandOutboxEvent::getConfirmedAt, confirmedAt)
                .set(AgentCommandOutboxEvent::getErrorMsg, null)
                .update();
        if (!ok) {
            log.warn("markConfirmed skipped: outbox id={} not in SENT (race / already processed)", id);
        }
    }

    /**
     * 发送失败：保持 PENDING，累计 {@code retry_count} 并设置下次可扫时间。
     *
     * <p>退避公式：{@code baseBackoffSeconds * 2^retryCount}，
     * 由 Relay 任务按 {@link #listReadyForRelay(int)} 的时间窗口自然回流。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(Long id, String error, int retryCount, OffsetDateTime nextRetryAt) {
        if (id == null) {
            return;
        }
        boolean ok = lambdaUpdate()
                .eq(AgentCommandOutboxEvent::getId, id)
                .eq(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.PENDING)
                .set(AgentCommandOutboxEvent::getRetryCount, retryCount)
                .set(AgentCommandOutboxEvent::getNextRetryAt, nextRetryAt)
                .set(AgentCommandOutboxEvent::getErrorMsg, truncate(error))
                .update();
        if (!ok) {
            log.warn("markFailed skipped: outbox id={} not in PENDING (race / already processed)", id);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailedFromSent(Long id, String error, int retryCount, OffsetDateTime nextRetryAt) {
        if (id == null) {
            return;
        }
        boolean ok = lambdaUpdate()
                .eq(AgentCommandOutboxEvent::getId, id)
                .eq(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.SENT)
                .set(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.PENDING)
                .set(AgentCommandOutboxEvent::getRetryCount, retryCount)
                .set(AgentCommandOutboxEvent::getNextRetryAt, nextRetryAt)
                .set(AgentCommandOutboxEvent::getErrorMsg, truncate(error))
                .update();
        if (!ok) {
            log.warn("markFailedFromSent skipped: outbox id={} not in SENT (race / already processed)", id);
        }
    }

    /**
     * 终态失败：超过 {@code maxRetry} 后标记 FAILED，不再扫描。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markFinalFailed(Long id, String error, int retryCount) {
        if (id == null) {
            return;
        }
        boolean ok = lambdaUpdate()
                .eq(AgentCommandOutboxEvent::getId, id)
                .eq(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.PENDING)
                .set(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.FAILED)
                .set(AgentCommandOutboxEvent::getRetryCount, retryCount)
                .set(AgentCommandOutboxEvent::getErrorMsg, truncate(error))
                .update();
        if (!ok) {
            log.warn("markFinalFailed skipped: outbox id={} not in PENDING (race / already processed)", id);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFinalFailedFromSent(Long id, String error, int retryCount) {
        if (id == null) {
            return;
        }
        boolean ok = lambdaUpdate()
                .eq(AgentCommandOutboxEvent::getId, id)
                .eq(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.SENT)
                .set(AgentCommandOutboxEvent::getStatus, AgentCommandOutboxStatus.FAILED)
                .set(AgentCommandOutboxEvent::getRetryCount, retryCount)
                .set(AgentCommandOutboxEvent::getErrorMsg, truncate(error))
                .update();
        if (!ok) {
            log.warn("markFinalFailedFromSent skipped: outbox id={} not in SENT (race / already processed)", id);
        }
    }

    /**
     * 将 {@link ExecutionCommandMqMessage} 拍平为 Outbox 表的 {@code jsonb} payload。
     *
     * <p>字段与消费端 {@code MqExecutionCommandConsumer.onMessage} 期望的 JSON 字段一一对应。
     * 不引入 DTO → JSON → JSON 三段往返，简化排障（DB 直查即看字段）。</p>
     */
    private Map<String, Object> toPayload(ExecutionCommandMqMessage message) {
        if (message == null) {
            return new HashMap<>();
        }
        Map<String, Object> map = new HashMap<>();
        map.put("recordId", message.getRecordId());
        map.put("eventId", message.getEventId());
        map.put("subTaskId", message.getSubTaskId());
        map.put("agentId", message.getAgentId());
        map.put("trigger", message.getTrigger());
        map.put("accessType", message.getAccessType());
        return map;
    }

    /**
     * 截断超长错误信息，避免 outbox 单行被异常堆栈撑爆。
     */
    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        if (error.length() <= 1000) {
            return error;
        }
        return error.substring(0, 1000);
    }
}
