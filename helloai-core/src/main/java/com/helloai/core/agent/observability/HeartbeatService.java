package com.helloai.core.agent.observability;

import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Agent 心跳服务（v2.4 阶段 4 升级）。
 *
 * <p>三件套心跳：
 * <ul>
 *   <li>last_seen_at（DB）— heartbeat/拉取/ack 即刷新，2 秒级</li>
 *   <li>last_active_at（DB）— start/submit/claim 即刷新，按需</li>
 *   <li>Redis TTL（缓存）— agent:heartbeat:{id} = last_seen_at，5 分钟过期</li>
 * </ul>
 * </p>
 *
 * <p>三态即时写回（方案 B，避免 Reconcile 滞后）：
 * <ul>
 *   <li>ONLINE — last_seen_at 和 last_active_at 都在 5 分钟内</li>
 *   <li>IDLE — last_seen_at 5 分钟内，last_active_at 超过 5 分钟</li>
 *   <li>OFFLINE — last_seen_at 超过 5 分钟或空</li>
 *   <li>SLEEPING — 管理员手动设置，系统不会自动设</li>
 * </ul>
 * </p>
 *
 * <p>SLEEPING 防护：seen() 不覆盖 SLEEPING 状态，只更新 last_seen_at。
 * OFFLINE 恢复时清 offline_reason/offline_at。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private final AgentMapper agentMapper;
    private final StringRedisTemplate redis;

    /** Redis 缓存键前缀 + agentId。 */
    public static final String HB_KEY_PREFIX = "agent:heartbeat:";
    /** Redis TTL = 5 分钟（与离线判定阈值一致）。 */
    public static final Duration HB_TTL = Duration.ofMinutes(5);

    /**
     * 心跳刷新（v2.4 升级版）。
     *
     * <ol>
     *   <li>写 Redis TTL（即使后续 DB 失败，TTL 仍能告诉 Reconcile "刚有人见过"）</li>
     *   <li>更新 DB last_seen_at</li>
     *   <li>若是 SLEEPING，只刷 seen_at，不覆盖 online_status</li>
     *   <li>否则即时写回三态（ONLINE/IDLE/OFFLINE），从 OFFLINE 恢复时清 offline_reason/offline_at</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public void seen(Long agentId) {
        // 1) Redis TTL：失败不抛，整体事务仍可继续
        try {
            redis.opsForValue().set(
                    HB_KEY_PREFIX + agentId,
                    Instant.now().toString(),
                    HB_TTL.getSeconds(),
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis TTL 写入失败（不影响心跳）: agentId={}, err={}", agentId, e.getMessage());
        }

        // 2) DB 读取
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            log.warn("心跳目标 Agent 不存在: agentId={}", agentId);
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        agent.setLastSeenTime(now);

        // 3) SLEEPING 防护：只刷 seen_at，不动 online_status
        if (agent.getOnlineStatus() == AgentOnlineStatus.SLEEPING) {
            agentMapper.updateById(agent);
            log.debug("heartbeat seen(SLEEPING): agentId={}", agentId);
            return;
        }

        // 4) 三态即时写回（方案 B）
        AgentOnlineStatus computed = checkOnlineStatus(agent);
        // 从 OFFLINE 恢复时清 offline_reason/offline_at
        if (agent.getOnlineStatus() == AgentOnlineStatus.OFFLINE
                && computed != AgentOnlineStatus.OFFLINE) {
            agent.setOfflineReason(null);
            agent.setOfflineTime(null);
            log.info("Agent 从 OFFLINE 恢复: agentId={}, newStatus={}", agentId, computed);
        }
        agent.setOnlineStatus(computed);
        agentMapper.updateById(agent);
        log.debug("heartbeat seen: agentId={}, onlineStatus={}", agentId, computed);
    }

    /**
     * 活跃刷新（任务执行时调用）：更新 last_active_at。
     *
     * <p>注意：active() 不强制刷新 last_seen_at（active 是任务执行的副产物，
     * 而 seen 才是连接存活的标志）；但若在线状态已离线，active 会顺带刷 seen_at
     * 让 Agent 回到 ONLINE。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void active(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) return;

        OffsetDateTime now = OffsetDateTime.now();
        agent.setLastActiveTime(now);

        // SLEEPING 防护：不动 online_status
        if (agent.getOnlineStatus() == AgentOnlineStatus.SLEEPING) {
            agentMapper.updateById(agent);
            return;
        }

        // active 通常意味着 Agent 在做事，应视为 ONLINE
        // 若 last_seen_at 还在 5 分钟内，把 online_status 提升到 ONLINE
        if (agent.getLastSeenTime() != null
                && agent.getLastSeenTime().isAfter(now.minusMinutes(5))) {
            if (agent.getOnlineStatus() == AgentOnlineStatus.OFFLINE) {
                agent.setOfflineReason(null);
                agent.setOfflineTime(null);
            }
            agent.setOnlineStatus(AgentOnlineStatus.ONLINE);
        }
        agentMapper.updateById(agent);
        log.debug("heartbeat active: agentId={}", agentId);
    }

    /**
     * 三态判定（纯计算，不写 DB）。
     *
     * <p>判定规则：
     * <ul>
     *   <li>last_seen_at 空 或 超过 5 分钟 → OFFLINE</li>
     *   <li>last_seen_at 5 分钟内 且 last_active_at 超过 5 分钟 → IDLE</li>
     *   <li>last_seen_at 5 分钟内 且 last_active_at 5 分钟内 → ONLINE</li>
     * </ul>
     * </p>
     */
    public AgentOnlineStatus checkOnlineStatus(Agent agent) {
        return checkOnlineStatus(agent, OffsetDateTime.now());
    }

    /**
     * 三态判定（带 now 参数，便于测试与回放）。
     */
    public AgentOnlineStatus checkOnlineStatus(Agent agent, OffsetDateTime now) {
        if (agent == null) return AgentOnlineStatus.OFFLINE;
        OffsetDateTime cutoff = now.minusMinutes(5);
        OffsetDateTime lastSeen = agent.getLastSeenTime();
        if (lastSeen == null || lastSeen.isBefore(cutoff)) {
            return AgentOnlineStatus.OFFLINE;
        }
        OffsetDateTime lastActive = agent.getLastActiveTime();
        if (lastActive != null && lastActive.isAfter(cutoff)) {
            return AgentOnlineStatus.ONLINE;
        }
        return AgentOnlineStatus.IDLE;
    }

    /**
     * 检查 Agent 是否处于活跃可调度状态（ONLINE 或 IDLE）。
     * SLEEPING / OFFLINE 不参与调度。
     */
    public boolean isSchedulable(Agent agent) {
        return agent != null
                && agent.getStatus() == AgentStatus.ACTIVE
                && agent.getOnlineStatus() != AgentOnlineStatus.SLEEPING
                && agent.getOnlineStatus() != AgentOnlineStatus.OFFLINE;
    }

    /**
     * 检查 Redis TTL 是否仍在（用于 Reconcile 的快速预筛）。
     *
     * @return true-TTL 存在 / false-TTL 缺失或 Redis 异常
     */
    public boolean isRedisAlive(Long agentId) {
        try {
            Boolean has = redis.hasKey(HB_KEY_PREFIX + agentId);
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
            log.warn("Redis 检查失败: agentId={}, err={}", agentId, e.getMessage());
            return false;
        }
    }
}
