package com.helloai.core.agent.service;

import com.helloai.common.config.AgentFallbackProperties;
import com.helloai.common.config.AgentHealthProperties;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * N11 外部 Agent 阈值回退 — 失败/成功计数与判定。
 *
 * <p>该 Service 是 N11 闭环的"计数侧"：
 * <ul>
 *   <li>{@link #recordFailure(Long)} / {@link #recordSuccess(Long)} 由失败/成功路径调用；</li>
 *   <li>{@link #markFallbackTriggered(Long)} 由 {@code ExternalAgentFallbackTask}
 *       在回退已经触发后调用，避免后续 cycle 重复触发；</li>
 *   <li>{@link #shouldFallback(Agent)} / {@link #findFallbackCandidates()} 由
 *       {@code ExternalAgentFallbackTask} 周期调用，做候选扫描 + cooldown 判定。</li>
 * </ul>
 * </p>
 *
 * <p><b>为什么计数与扫描分开？</b>计数路径在事务内需要立即落库（避免重启丢数），
 * 扫描路径则由独立周期任务执行（避免扫描阻塞主调用链）。</p>
 *
 * @see com.helloai.common.config.AgentFallbackProperties
 * @see com.helloai.job.task.ExternalAgentFallbackTask
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalAgentFailureTracker {

    private final AgentMapper agentMapper;
    private final AgentFallbackProperties properties;
    private final AgentHealthProperties healthProperties;

    // ══════════════════════════════════════════════════════════════
    //  计数侧 — 失败/成功路径调用
    // ══════════════════════════════════════════════════════════════

    /**
     * 记录一次失败：原子累加 consecutive_failure_count + 刷新 last_failure_at。
     *
     * <p>仅在 Agent.access_type = CLI_CLIENT 时生效；
     * API_KEY_LLM / WEB_BROWSER 不参与 N11 闭环（被 SQL 条件直接跳过）。</p>
     *
     * <p>使用 {@code REQUIRES_NEW} 事务：即便外层主链路事务回滚，失败计数也保留，
     * 避免"任务被回滚后 Agent 不再被计入失败"导致阈值永远触发不了。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(Long agentId) {
        if (!isEnabled() || agentId == null) {
            return;
        }
        try {
            int updated = agentMapper.incrementConsecutiveFailure(agentId, OffsetDateTime.now());
            if (updated == 0) {
                log.debug("recordFailure skipped: agentId={} (not CLI_CLIENT or deleted)", agentId);
            } else if (log.isDebugEnabled()) {
                log.debug("recordFailure: agentId={} consecutive_failure_count +1", agentId);
            }
        } catch (Exception e) {
            log.error("recordFailure 失败: agentId={}", agentId, e);
        }
    }

    /**
     * 记录一次成功：原子重置 consecutive_failure_count = 0 + 清空 last_failure_at。
     *
     * <p>同样仅对 CLI_CLIENT 生效；调用方应保证传入的 agentId 来自失败/成功报告上下文，
     * 避免对 API_KEY_LLM Agent 误调（SQL 会自然跳过）。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordSuccess(Long agentId) {
        if (!isEnabled() || agentId == null) {
            return;
        }
        try {
            int updated = agentMapper.resetConsecutiveFailure(agentId, OffsetDateTime.now());
            if (updated == 0) {
                log.debug("recordSuccess skipped: agentId={} (not CLI_CLIENT or deleted)", agentId);
            } else if (log.isDebugEnabled()) {
                log.debug("recordSuccess: agentId={} consecutive_failure_count 重置为 0", agentId);
            }
        } catch (Exception e) {
            log.error("recordSuccess 失败: agentId={}", agentId, e);
        }
    }

    /**
     * 标记回退已触发：清零计数 + 写入 last_fallback_at。
     *
     * <p>由 {@code ExternalAgentFallbackTask} 在真正触发重新分发后调用，
     * 配合 cooldown 判定保证"一次扫描只触发一次"。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFallbackTriggered(Long agentId) {
        if (!isEnabled() || agentId == null) {
            return;
        }
        int updated = agentMapper.markFallbackTriggered(agentId, OffsetDateTime.now());
        if (updated == 0) {
            log.warn("markFallbackTriggered skipped: agentId={} (not CLI_CLIENT or deleted)", agentId);
        } else {
            log.info("markFallbackTriggered: agentId={} last_fallback_at=NOW", agentId);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  扫描侧 — 周期任务调用
    // ══════════════════════════════════════════════════════════════

    /**
     * 扫描所有处于"回退候选"状态的 Agent。
     *
     * <p>候选定义（详见 {@code AgentMapper.selectFallbackCandidates}）：
     * <ol>
     *   <li>{@code access_type = CLI_CLIENT}</li>
     *   <li>{@code consecutive_failure_count >= threshold}</li>
     *   <li>cooldown 已过：{@code last_fallback_at IS NULL} 或早于 {@code now - cooldown}</li>
     *   <li>v2.6 §4.1：心跳新鲜——{@code last_seen_time} 非空且晚于
     *       {@code now - healthProperties.offlineMinutes}；与 AgentSelector
     *       和 AgentHealthCheckTask 共用同一阈值，避免 SQL 与 Java 侧规则漂移</li>
     * </ol>
     */
    public java.util.List<Agent> findFallbackCandidates() {
        if (!isEnabled()) {
            return java.util.List.of();
        }
        OffsetDateTime cooldownCutoff = OffsetDateTime.now()
                .minusMinutes(properties.getCooldownMinutes());
        OffsetDateTime lastSeenCutoff = resolveLastSeenCutoff();
        return agentMapper.selectFallbackCandidates(
                properties.getFailureThreshold(), cooldownCutoff, lastSeenCutoff);
    }

    /**
     * 纯函数式判定：当前 Agent 是否构成回退候选（不查 DB）。
     *
     * <p>给上层在已读到 Agent 实体时复用，避免重复 SQL 扫描。
     * 判定规则与 {@link #findFallbackCandidates()} 完全一致，包括 v2.6 §4.1
     * 心跳新鲜度检查：CLI_CLIENT 必须在 {@code AgentHealthProperties.offlineMinutes}
     * 之内有过心跳（{@code last_seen_time > now - offlineMinutes}）。</p>
     */
    public boolean shouldFallback(Agent agent) {
        if (!isEnabled() || agent == null) {
            return false;
        }
        if (agent.getAccessType() == null
                || agent.getAccessType() != com.helloai.common.constant.AgentAccessType.CLI_CLIENT) {
            return false;
        }
        int count = agent.getConsecutiveFailureCount() == null ? 0 : agent.getConsecutiveFailureCount();
        if (count < properties.getFailureThreshold()) {
            return false;
        }
        OffsetDateTime cooldownCutoff = OffsetDateTime.now()
                .minusMinutes(properties.getCooldownMinutes());
        OffsetDateTime lastFallbackAt = agent.getLastFallbackTime();
        if (lastFallbackAt != null && !lastFallbackAt.isBefore(cooldownCutoff)) {
            return false;
        }
        // v2.6 §4.1：CLI_CLIENT 必须心跳新鲜，避免把"刚被死但还没标 OFFLINE"
        // 的 Agent 当作回退候选；与 SQL selectFallbackCandidates 规则一致。
        // 阈值 <= 0 表示关闭过滤（逃生口）：null last_seen_time 也视为可回退。
        int offlineMinutes = healthProperties != null
                ? healthProperties.getOfflineMinutes() : 0;
        if (offlineMinutes <= 0) {
            return true;
        }
        OffsetDateTime lastSeen = agent.getLastSeenTime();
        if (lastSeen == null) {
            return false;
        }
        OffsetDateTime lastSeenCutoff = OffsetDateTime.now().minusMinutes(offlineMinutes);
        return lastSeen.isAfter(lastSeenCutoff);
    }

    /**
     * 计算心跳新鲜度截止时间，与 AgentSelector / AgentHealthCheckTask
     * 共用 {@link AgentHealthProperties#getOfflineMinutes()}。阈值 <= 0
     * 时返回 {@link OffsetDateTime#MIN} 表示"不过滤"。
     */
    private OffsetDateTime resolveLastSeenCutoff() {
        int minutes = healthProperties != null
                ? healthProperties.getOfflineMinutes() : 0;
        if (minutes <= 0) {
            return OffsetDateTime.MIN;
        }
        return OffsetDateTime.now().minusMinutes(minutes);
    }

    private boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }
}
