package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * Agent 值班租约实体。
 *
 * <p>AgentHub 最小骨架：值班态事实源。</p>
 *
 * <p>每条记录代表一次 Agent "打卡上班"的完整生命周期：
 * 从 {@code status=ACTIVE} 开始，到 {@code status=CLOSED/EXPIRED} 结束。
 * 一个 Agent 同时最多有一条 ACTIVE 租约。</p>
 *
 * <p>本轮不做：checkIn/checkOut、selector 接入、dashboard。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_duty_lease")
public class AgentDutyLease extends BaseEntity {

    /** 关联的 Agent ID。 */
    private Long agentId;

    /** 值班会话标识（同一次 checkIn 的 lease 共享同一个 session_id）。 */
    private String sessionId;

    /** 工作模式：预留字段，AgentHub 后续扩展。 */
    private String workMode;

    /** 最大并发子任务数。 */
    private Integer maxConcurrent;

    /** 租约状态。 */
    private AgentDutyLeaseStatus status;

    /** 租约开始时间。 */
    private OffsetDateTime startTime;

    /** 最近一次续约时间。 */
    private OffsetDateTime lastRenewTime;

    /** 租约过期时间（start_time + lease TTL）。 */
    private OffsetDateTime expireTime;

    /**
     * 签发租约时解析出的租约窗口（分钟，G-015 B4.3 / V94）。
     *
     * <p>持久化后 {@code adaptiveRenew} 的空闲续约复用同一窗口，不再每轮按表现分重算——
     * 消除 P2-10「租约窗口反复跳变、与 checkIn 承诺的 expiresAt 口径不一致」。</p>
     *
     * <p>{@code NULL} 表示历史行未持久化，空闲续约回退既有动态推断（行为与迁移前一致）。
     * 续约不改写本字段：在飞保活用的 {@code maxTtlMinutes} 只体现在 {@code expireTime} 上。</p>
     */
    private Integer ttlMinutes;

    /** 关闭原因（仅在 status=CLOSED 时填写）。 */
    private String closeReason;
}
