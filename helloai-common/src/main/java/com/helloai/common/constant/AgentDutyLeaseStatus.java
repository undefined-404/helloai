package com.helloai.common.constant;

/**
 * Agent 值班租约状态。
 *
 * <p>AgentHub 最小骨架：值班态事实源.</p>
 */
public enum AgentDutyLeaseStatus {

    /** 当前有效，Agent 处于值班态。 */
    ACTIVE,

    /** 正常关闭（Agent 主动签退 / checkOut）。 */
    CLOSED,

    /** 超时过期（未及时续约，由系统标记）。 */
    EXPIRED
}
