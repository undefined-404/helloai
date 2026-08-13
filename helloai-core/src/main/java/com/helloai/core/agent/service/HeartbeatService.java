package com.helloai.core.agent.service;

import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.core.agent.entity.Agent;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Agent 心跳服务（v2.4+）：Redis TTL + DB last_seen_time 双写，三态在线判定与调度可用性判断。
 */
public interface HeartbeatService {

    /** Redis 缓存键前缀 + agentId。 */
    String HB_KEY_PREFIX = "agent:heartbeat:";
    /** Redis TTL = 5 分钟（与离线判定阈值一致）。 */
    Duration HB_TTL = Duration.ofMinutes(5);

    /**
     * 心跳刷新：写 Redis TTL → 更新 DB last_seen_at → 三态重算
     * （SLEEPING 只刷 seen_at 不覆盖 online_status；OFFLINE 恢复时清 offline 原因）。
     */
    void seen(Long agentId);

    /**
     * 业务活跃心跳：复用 seen() 双写，并单独刷新 last_active_at
     * （"最近一次业务执行时刻"语义，区别于 seen 的"连接存活"语义）。
     */
    void active(Long agentId);

    /**
     * 三态判定（纯计算，不写 DB）：OFFLINE / IDLE / ONLINE。
     */
    AgentOnlineStatus checkOnlineStatus(Agent agent);

    /**
     * 三态判定（带 now 参数，便于测试与回放）。
     */
    AgentOnlineStatus checkOnlineStatus(Agent agent, OffsetDateTime now);

    /**
     * 检查 Agent 是否处于活跃可调度状态（ONLINE 或 IDLE；SLEEPING / OFFLINE 不参与调度）。
     */
    boolean isSchedulable(Agent agent);

    /**
     * 检查 Redis TTL 是否仍在（用于 Reconcile 的快速预筛）。
     *
     * @return true-TTL 存在 / false-TTL 缺失或 Redis 异常
     */
    boolean isRedisAlive(Long agentId);
}
