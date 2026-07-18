package com.helloai.core.shared.event;

import lombok.Getter;

/**
 * 值班租约关闭事件（AgentHub V3 门铃 PR-3）。
 *
 * <p>当 Agent 主动 checkOut（{@code closeLease}）或租约到期被巡检翻为 EXPIRED
 * （{@code expireLeases}）时，由 {@code AgentDutyLeaseService} 在事务内发布。
 * 门铃侧 {@code DoorbellDutyListener} 在 {@code AFTER_COMMIT} 监听后主动断开该 Agent
 * 的门铃连接——离岗即挂电话，避免继续向已下班 / 到期的 Agent 响铃。</p>
 *
 * <p>与 {@code InboxMessageCreatedEvent} 对称：都是收口在 Service 层的领域事件，
 * 借 {@code AFTER_COMMIT} 保证"先落库 / 先改状态、后动门铃"，事务回滚则事件不投递。</p>
 */
@Getter
public class DutyLeaseClosedEvent {

    /** 下班 / 到期的 Agent ID。 */
    private final Long agentId;

    /** 关闭原因（如 checkOut 传入值 / {@code lease_expired}）。 */
    private final String reason;

    public DutyLeaseClosedEvent(Long agentId, String reason) {
        this.agentId = agentId;
        this.reason = reason;
    }
}
