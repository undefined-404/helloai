package com.helloai.api.dto.duty;

import com.helloai.common.constant.AgentDutyLeaseStatus;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 值班租约列表项（AgentHub V1 P1 值班报表数据源）。
 *
 * <p>对应 {@code agent_duty_lease} 一条租约的对外只读视图，
 * 额外冗余 {@code agentName} 便于看板直接展示。</p>
 */
@Data
public class DutyLeaseResponse {

    /** 租约主键。 */
    private Long id;

    /** 关联的 Agent ID。 */
    private Long agentId;

    /** Agent 名称（关联 agent 表冗余，Agent 已删除时为 null）。 */
    private String agentName;

    /** 值班会话标识。 */
    private String sessionId;

    /** 工作模式。 */
    private String workMode;

    /** 最大并发子任务数。 */
    private Integer maxConcurrent;

    /** 租约状态：ACTIVE / CLOSED / EXPIRED。 */
    private AgentDutyLeaseStatus status;

    /** 值班开始时间。 */
    private OffsetDateTime startedAt;

    /** 最近一次续约时间。 */
    private OffsetDateTime lastRenewedAt;

    /** 租约过期时间。 */
    private OffsetDateTime expiresAt;

    /** 关闭原因（仅 CLOSED / EXPIRED 时有值）。 */
    private String closeReason;
}
