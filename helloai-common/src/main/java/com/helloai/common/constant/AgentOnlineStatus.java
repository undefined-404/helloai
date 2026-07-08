package com.helloai.common.constant;

/**
 * Agent 计算态在线状态（与 AgentStatus 管理态分离）。
 *
 * <ul>
 *   <li>ONLINE    — last_seen_at 与 last_active_at 都在 5 分钟内</li>
 *   <li>IDLE      — last_seen_at 在 5 分钟内，last_active_at 超过 5 分钟或为空</li>
 *   <li>OFFLINE   — last_seen_at 超过 5 分钟或为空（由 HealthCheckTask 标记）</li>
 *   <li>SLEEPING  — 管理员手动设置，系统不会自动设</li>
 * </ul>
 *
 * 鉴权只看 AgentStatus（ACTIVE/DISABLED），不关心 OnlineStatus。
 * 调度过滤、任务分配看 OnlineStatus。
 */
public enum AgentOnlineStatus {
    ONLINE,
    IDLE,
    OFFLINE,
    SLEEPING
}
