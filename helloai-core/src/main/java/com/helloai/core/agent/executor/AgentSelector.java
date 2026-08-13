package com.helloai.core.agent.executor;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.AgentHealthProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.WorkMode;
import com.helloai.core.agent.SkillNormalizer;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.core.system.service.CredentialVaultService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Agent 选择器（v2.4 §4.6）。
 *
 * <p>在熔断降级 / 主 Agent 不可用时，从同角色 Agent 中选择替代者。
 * 自动跳过 SLEEPING、OFFLINE、熔断中的 Agent，优先选分数最高的可用 Agent。</p>
 *
 * @see com.helloai.core.agent.dispatcher.ResilientDispatcher
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSelector {

    private final AgentService agentService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final AgentDispatchProperties agentDispatchProperties;
    private final AgentHealthProperties agentHealthProperties;
    private final AgentDutyLeaseService agentDutyLeaseService;
    private final CredentialVaultService credentialVaultService;
    private final ConcurrencyQuotaService concurrencyQuotaService;

    /**
     * 从指定角色的 Agent 中选取首选执行器（用于初始分配）。
     *
     * <p>注意：本方法只负责“选人”，不落库、不发布事件。
     * 分配与熔断降级应由 {@link com.helloai.core.agent.dispatcher.ResilientDispatcher} 统一完成。</p>
     */
    public Agent pickPreferred(AgentRole role) {
        return pickPreferred(role, null);
    }

    /**
     * V47（§6.58 P1）：带任务级约束的首选选人。
     *
     * <p>在 {@link #pickPreferred(AgentRole)} 基础上追加
     * {@link AgentSelectionConstraints} 过滤（执行者白名单 + 技能 AND 匹配），
     * 供任务指定 executorAgentIds / required_skills 时约束初始分配。</p>
     *
     * @param role        Agent 角色；为 null 时不限定角色
     * @param constraints 任务级选人约束；null 表示不约束（与旧行为一致）
     * @return 首选 Agent，无可选时返回 null
     */
    public Agent pickPreferred(AgentRole role, AgentSelectionConstraints constraints) {
        List<Agent> candidates;
        if (role != null) {
            candidates = agentService.listByRole(role);
        } else {
            candidates = agentService.listActive();
        }
        return pickFromCandidates(candidates, null, constraints);
    }

    /**
     * 从同角色 Agent 中选取替代者。
     *
     * <p>过滤规则（按优先级）：
     * <ol>
     *   <li>跳过 excludeAgentId（被熔断或不可用的原 Agent）</li>
     *   <li>V47：跳过不满足任务级约束的 Agent（{@link AgentSelectionConstraints}——
     *       执行者白名单外、或未声明 required_skills 全部技能的 Agent）</li>
     *   <li>跳过 SLEEPING 状态</li>
     *   <li>跳过 OFFLINE 状态（v2.6 §4.1 由 markOfflineIfStale + Reconcile保证唯一性，
     *       API_KEY_LLM 豁免）</li>
     *   <li>v2.6 §4.1：心跳新鲜度过滤—— last_seen_time 距今超过
     *       {@link AgentHealthProperties#getOfflineMinutes()}（默认 5 分钟，
     *       对齐 Redis 心跳 TTL）的 Agent 被跳过，即使 online_status 仍是 ONLINE；
     *       防止选人拿到“刚被死但还未来得及被 Reconcile 标 OFFLINE”的 Agent。
     *       API_KEY_LLM 始终视为新鲜（不需要运行时心跳）。</li>
     *   <li>跳过 status != ACTIVE（已禁用的 Agent）</li>
     *   <li>跳过无启用态托管凭证的 API_KEY_LLM Agent（封堵双重豁免下的无凭证劫持）</li>
     *   <li>跳过熔断器已打开的 Agent（per-agent 维度）</li>
     *   <li>N12 P1 STRICT 独占报锁：跳过当前以 STRICT 模式在岗的 Agent
     *       （不参与他人失败后的替补池，但可被初始/直接分配的任务命中）</li>
     *   <li>E2：跳过并发额度已满的 Agent（当前占用 &gt;= 声明额度时不再接收新任务；
     *       {@code enforceMaxConcurrent=false} 时跳过本检查，与 E2 前行为一致）</li>
     *   <li>按 score DESC 排序，选最高分</li>
     * </ol>
     *
     * @param excludeAgentId 需要排除的 Agent ID（原分配目标）
     * @param role           Agent 角色；为 null 时不限定角色
     * @return 可用替代 Agent，无可选时返回 null
     */
    public Agent pickAlternative(Long excludeAgentId, AgentRole role) {
        return pickAlternative(excludeAgentId, role, null);
    }

    /**
     * V47（§6.58 P1）：带任务级约束的替代选人。
     *
     * <p>在 {@link #pickAlternative(Long, AgentRole)} 基础上追加
     * {@link AgentSelectionConstraints} 过滤（执行者白名单 + 技能 AND 匹配），
     * 供任务指定 executorAgentIds / required_skills 时约束重分配链（含熔断降级替代）。</p>
     *
     * @param excludeAgentId 需要排除的 Agent ID（原分配目标）
     * @param role           Agent 角色；为 null 时不限定角色
     * @param constraints    任务级选人约束；null 表示不约束（与旧行为一致）
     * @return 可用替代 Agent，无可选时返回 null
     */
    public Agent pickAlternative(Long excludeAgentId, AgentRole role, AgentSelectionConstraints constraints) {
        List<Agent> candidates;
        if (role != null) {
            candidates = agentService.listByRole(role);
        } else {
            candidates = agentService.listActive();
        }
        return pickFromCandidates(candidates, excludeAgentId, constraints);
    }

    private Agent pickFromCandidates(List<Agent> candidates, Long excludeAgentId,
                                     AgentSelectionConstraints constraints) {
        return candidates.stream()
                .filter(a -> excludeAgentId == null || !a.getId().equals(excludeAgentId))
                .filter(a -> constraints == null || constraints.allows(a))
                .filter(a -> agentDispatchProperties.getForceAccessType() == null
                        || (a.getAccessType() != null && a.getAccessType() == agentDispatchProperties.getForceAccessType()))
                .filter(a -> a.getOnlineStatus() != AgentOnlineStatus.SLEEPING)
                .filter(a -> a.getOnlineStatus() != AgentOnlineStatus.OFFLINE
                        || (a.getAccessType() != null && !a.getAccessType().requiresRuntimeLiveness()))
                .filter(this::isHeartbeatFresh)
                .filter(a -> !agentDispatchProperties.isRequireIdle() || agentService.inProgressCount(a.getId()) == 0)
                .filter(a -> !agentDispatchProperties.isEnforceMaxConcurrent()
                        || concurrencyQuotaService.canAccept(a.getId()))
                .filter(a -> a.getStatus() == AgentStatus.ACTIVE)
                .filter(this::hasUsableCredential)
                .filter(a -> !isOnStrictDuty(a.getId()))
                .filter(this::isCircuitClosed)
                .max(resolveComparator())
                .orElse(null);
    }

    /**
     * 心跳新鲜度检查（v2.6 §4.1）。
     *
     * <p>返回 true 表示 Agent 近期可见、参与选人：</p>
     * <ul>
     *   <li>API_KEY_LLM 类型 始终视为新鲜（requiresRuntimeLiveness=false，
     *       架构 §3.8 三层可用性）</li>
     *   <li>CLI_CLIENT：last_seen_time 距今 ≤
     *       {@link AgentHealthProperties#getOfflineMinutes()}（默认 5 分钟，
     *       对齐 Redis TTL）视为新鲜；last_seen_time=null 也视为陈旧</li>
     *   <li>阈值 ≤ 0 时视为关闭过滤（不推荐生产使用）</li>
     * </ul>
     *
     * <p>防御式：不因本检查本身报错而影响选人（如 last_seen_time 为 null
     * 造成 NPE 会被 try/catch 降级为不新鲜）。</p>
     *
     * <p>V25：改为 public 供 {@link com.helloai.core.agent.dispatcher.ResilientDispatcher}
     * 在 fast-fail 阶段复用，封堵"DB online_status 滞后 ONLINE 但 Agent 已死"的误派窗口。</p>
     */
    public boolean isHeartbeatFresh(Agent agent) {
        if (agent == null || agent.getId() == null) {
            return false;
        }
        try {
            // API_KEY_LLM / WEB_BROWSER 列为"不需运行时心跳"，始终视为新鲜
            AgentAccessType accessType = agent.getAccessType();
            if (accessType != null && !accessType.requiresRuntimeLiveness()) {
                return true;
            }
            int thresholdMinutes = agentHealthProperties.getOfflineMinutes();
            if (thresholdMinutes <= 0) {
                // 关闭过滤（逃生口）
                return true;
            }
            OffsetDateTime lastSeen = agent.getLastSeenTime();
            if (lastSeen == null) {
                return false;
            }
            OffsetDateTime cutoff = OffsetDateTime.now().minus(Duration.ofMinutes(thresholdMinutes));
            return lastSeen.isAfter(cutoff);
        } catch (Exception e) {
            log.debug("isHeartbeatFresh fallback to false for agent {}: {}",
                    agent.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 凭证可用性检查：API_KEY_LLM 候选必须在 credential_vault 中有启用态凭证。
     *
     * <p>API_KEY_LLM 享有心跳/OFFLINE 双重豁免，若不校验凭证，历史遗留的无凭证 Agent
     * 永远是合格候选，选中即执行 500。其它 accessType 不依赖 vault，直接放行。
     * 防御式：查询异常降级为排除（选中无凭证 Agent 必败，排除更安全）。</p>
     */
    private boolean hasUsableCredential(Agent agent) {
        if (agent == null || agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
            return true;
        }
        try {
            boolean has = credentialVaultService.hasActiveAgentCredential(agent.getId());
            if (!has) {
                log.debug("Agent {} 无启用态托管凭证，跳过选人", agent.getId());
            }
            return has;
        } catch (Exception e) {
            log.debug("hasUsableCredential fallback to false for agent {}: {}",
                    agent.getId(), e.getMessage());
            return false;
        }
    }

    private Comparator<Agent> resolveComparator() {
        // AgentHub V1 P0-B：“当前是否处于值班”作为软优先级最高一档。
        // 无硬拒绝：即使无任何值班 Agent，仍能从非值班候选中选出，
        // 保证与当前行为向后兼容（V1 未上线时 checkIn 未被调用，选择器表现与以前一致）。
        Comparator<Agent> dutyFirst = Comparator.comparingInt(this::dutyRank);
        if (!agentDispatchProperties.isPreferExternal()) {
            return dutyFirst.thenComparing(Agent::getScore, Comparator.nullsFirst(Comparator.naturalOrder()));
        }
        return dutyFirst
                .thenComparingInt(this::accessTypeRank)
                .thenComparing(Agent::getScore, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /**
     * 值班优先的 rank：ACTIVE lease 存在 → 1，否则 0。
     *
     * <p>max() 取最大，因此值班 Agent 优先。安全兵：isOnDuty 内部容忍 agentId==null。</p>
     */
    private int dutyRank(Agent agent) {
        if (agent == null || agent.getId() == null) {
            return 0;
        }
        try {
            return agentDutyLeaseService.isOnDuty(agent.getId()) ? 1 : 0;
        } catch (Exception e) {
            // 防御式：任何 lease 查询异常都不影响选择（降级为非值班处理）
            log.debug("dutyRank fallback to 0 for agent {}: {}", agent.getId(), e.getMessage());
            return 0;
        }
    }

    private int accessTypeRank(Agent agent) {
        if (agent == null || agent.getAccessType() == null) return 0;
        return switch (agent.getAccessType()) {
            case CLI_CLIENT -> 3;
            case API_KEY_LLM -> 2;
            case WEB_BROWSER -> 1;
        };
    }

    /**
     * N12 P1 STRICT 独占报锁（A2 第 1 段）语义：
     * 返回 true 表示该 Agent 当前以 STRICT 模式上岗，
     * 平台不应当把它列入他人失败/熔断后的替补池候选。
     *
     * <p>防御式：任何 lease 查询异常都降级为 false（不阻断选择）。</p>
     */
    private boolean isOnStrictDuty(Long agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            AgentDutyLease lease = agentDutyLeaseService.getActiveLease(agentId);
            if (lease == null) {
                return false;
            }
            return WorkMode.lenientParse(lease.getWorkMode()) == WorkMode.STRICT;
        } catch (Exception e) {
            log.debug("isOnStrictDuty fallback to false for agent {}: {}", agentId, e.getMessage());
            return false;
        }
    }

    /**
     * 检查 Agent 对应的熔断器是否处于关闭/半开状态。
     *
     * <p>熔断器命名规则：agentDispatch-{agentId}。
     * 如果该 Agent 尚未创建过熔断器（从未被调度过），视为可用。</p>
     */
    private boolean isCircuitClosed(Agent agent) {
        try {
            String cbName = "agentDispatch-" + agent.getId();
            circuitBreakerRegistry.find(cbName).ifPresent(cb -> {
                if (cb.getState() == CircuitBreaker.State.OPEN) {
                    log.debug("Agent {} 熔断器已打开，跳过: {}", agent.getId(), cbName);
                    throw new CircuitOpenException(cbName);
                }
            });
            return true;
        } catch (CircuitOpenException e) {
            return false;
        }
    }

    /**
     * 内部标记异常：熔断器已打开。
     */
    private static class CircuitOpenException extends RuntimeException {
        CircuitOpenException(String cbName) {
            super("CircuitBreaker OPEN: " + cbName);
        }
    }

    /**
     * 任务级选人约束（V47，§6.58 P1）。
     *
     * <p>由任务 {@code agent_policy.executorAgentIds} 与 {@code required_skills}
     * 构建，注入选人链：白名单限定 + 技能 AND 匹配。约束为 null 或字段为空时
     * 一律不限制，与旧行为完全一致。</p>
     */
    @Data
    public static class AgentSelectionConstraints {

        /** 执行者白名单；null/空 = 不限定；非空 = 只允许集合内的 Agent。 */
        private List<Long> allowedAgentIds;

        /** 任务要求技能；null/空 = 不限定；非空 = Agent.skills 归一化后必须全部包含（AND 语义，A3 同义词互命中）。 */
        private List<String> requiredSkills;

        public static AgentSelectionConstraints of(List<Long> allowedAgentIds, List<String> requiredSkills) {
            AgentSelectionConstraints constraints = new AgentSelectionConstraints();
            constraints.setAllowedAgentIds(allowedAgentIds);
            constraints.setRequiredSkills(requiredSkills);
            return constraints;
        }

        /** 无限制约束（等价于传 null，供调用方语义化表达"不约束"）。 */
        public static AgentSelectionConstraints unrestricted() {
            return new AgentSelectionConstraints();
        }

        /** 候选是否满足约束：白名单内（若有）且技能全匹配（若有）。防御式：agent 为 null 直接拒绝。 */
        public boolean allows(Agent agent) {
            if (agent == null) {
                return false;
            }
            if (allowedAgentIds != null && !allowedAgentIds.isEmpty()
                    && (agent.getId() == null || !allowedAgentIds.contains(agent.getId()))) {
                return false;
            }
            if (requiredSkills != null && !requiredSkills.isEmpty()) {
                List<String> skills = agent.getSkills();
                // A3：匹配前归一化（trim + 小写 + 同义词归并），"powershell"/"bash" 与 "shell" 互相命中
                if (skills == null || skills.isEmpty() || !SkillNormalizer.matches(skills, requiredSkills)) {
                    return false;
                }
            }
            return true;
        }
    }
}
