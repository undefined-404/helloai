package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AgentCommandOutboxStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Phase 2H ②a 引入 / Phase 2H ②b 扩展：
 * 执行命令 Outbox 行实体（{@code agent_command_outbox}）。
 *
 * <p>本实体与 {@link AgentOutboxEvent}（SubTask 状态变更事件）严格分层——前者负责
 * "执行命令 → MQ"的投递生命周期，后者负责 "SubTask 状态变更 → 通知"的事件生命周期。
 * 两者不共用表，不共用状态枚举，不共用 Service。</p>
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@link #eventId}：与 {@code ExecutionCommand.eventId} 一一对应，UK 防重投；</li>
 *   <li>{@link #aggregateType}：本表固定 {@code EXECUTION_COMMAND}，未来统一 outbox 时
 *       配合 INSERT SELECT 跨表迁移；</li>
 *   <li>{@link #aggregateId}：业务聚合根 ID，即 {@code agent_execution_record.id}；</li>
 *   <li>{@link #payload}：{@code ExecutionCommandMqMessage} 的 JSON 序列化形式，
 *       与 {@code MqExecutionCommandConsumer.onMessage} 消费端 {@code objectMapper.readValue(byte[])} 对称；</li>
 *   <li>{@link #status}：PENDING / SENT / CONFIRMED / FAILED 四态（②a 三态，②b 新增 CONFIRMED），
 *       由 {@link com.helloai.common.constant.AgentCommandOutboxStatus} 约束；</li>
 *   <li>{@link #nextRetryTime}：失败后下次可扫时间，按
 *       {@code baseBackoff * 2^retryCount} 指数退避；</li>
 *   <li>{@link #lastSentTime}（②b 新增）：最近一次成功发送给 broker 的时间，
 *       是 {@code listExpiredSentForRetry} 计算 Confirm 超时的依据；</li>
 *   <li>{@link #confirmedTime}（②b 新增）：broker ACK 回写完成时间，写入即视作 CONFIRMED。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_command_outbox", autoResultMap = true)
public class AgentCommandOutboxEvent extends BaseEntity {

    /** 消息唯一标识（与 ExecutionCommand.eventId 对齐，作为唯一索引防重投）。 */
    private String eventId;

    /**
     * 业务聚合类型；本表固定 {@code EXECUTION_COMMAND}。
     *
     * <p>暂以 {@link String} 持久化字段呈现，不参与 MyBatis-Plus {@code IEnum} 数值映射——
     * 列类型为 {@code VARCHAR(64)}，未来可能扩展其它类型（如 {@code SUB_TASK_STATUS_CHANGE}）。</p>
     */
    private String aggregateType;

    /** 业务聚合根 ID（{@code agent_execution_record.id}）。 */
    private String aggregateId;

    /** 序列化后的 MQ 消息体（与 {@code ExecutionCommandMqMessage} 字段对齐）。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    /** 投递状态。 */
    private AgentCommandOutboxStatus status;

    /** 已重试次数。 */
    private Integer retryCount;

    /** 下一次可重试时间（指数退避）。 */
    private OffsetDateTime nextRetryTime;

    private OffsetDateTime lastSentTime;

    private OffsetDateTime confirmedTime;

    /** 最后一次失败原因（成功时为空）。 */
    private String errorMsg;
}
