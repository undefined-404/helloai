package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.AgentCommandOutboxEvent;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 执行命令 Outbox（{@code agent_command_outbox}）服务。
 *
 * <p><b>职责边界</b>：只承载"执行命令 → MQ"的投递生命周期；
 * 与 {@link AgentOutboxService}（SubTask 状态变更通知）严格分层——
 * 不共用表、不共用枚举、不共用 Service。</p>
 */
public interface AgentCommandOutboxService extends IService<AgentCommandOutboxEvent> {

    /**
     * 在调用方事务内写入一条 PENDING outbox 行。
     *
     * <p>本方法不加事务，由调用方（{@code ExecutionCommandService} 的"同事务写
     * ExecutionCommand + Outbox"路径）持有外层事务；{@code eventId} 与
     * {@link ExecutionCommand#getEventId()} 对齐作为唯一索引防重投。</p>
     */
    AgentCommandOutboxEvent createPending(ExecutionCommand command, ExecutionCommandMqMessage message);

    /**
     * 拉取一批"到时间可重试、未超阈值"的 PENDING 行。
     */
    List<AgentCommandOutboxEvent> listReadyForRelay(int limit);

    /**
     * 扫出 SENT 后超过 Confirm 超时窗口、仍未确认的行，应对重启后 in-flight future 丢失的恢复。
     */
    List<AgentCommandOutboxEvent> listExpiredSentForRetry(int limit);

    /**
     * 发送成功：标记 SENT 并写入 {@code last_sent_at}。
     */
    void markSent(Long id, OffsetDateTime sentAt);

    /**
     * broker ACK 回写 CONFIRMED。
     */
    void markConfirmed(Long id, OffsetDateTime confirmedAt);

    /**
     * 发送失败：保持 PENDING，累计 {@code retry_count} 并按指数退避设置 {@code next_retry_at}。
     */
    void markFailed(Long id, String error, int retryCount, OffsetDateTime nextRetryAt);

    /**
     * SENT → PENDING 回退，用于 NACK / return / confirm-timeout。
     */
    void markFailedFromSent(Long id, String error, int retryCount, OffsetDateTime nextRetryAt);

    /**
     * 超过 {@code maxRetry} 标记 FAILED 终结，不再扫描。
     */
    void markFinalFailed(Long id, String error, int retryCount);

    /**
     * SENT → FAILED 终态，与 {@link #markFailedFromSent} 共同覆盖"发送后失败"两路收尾。
     */
    void markFinalFailedFromSent(Long id, String error, int retryCount);
}
