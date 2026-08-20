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

    /** 关闭原因（仅在 status=CLOSED 时填写）。 */
    private String closeReason;
}
