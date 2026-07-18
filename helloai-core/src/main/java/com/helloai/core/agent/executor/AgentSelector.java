package com.helloai.core.agent.executor;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.WorkMode;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Agent 选择器（v2.4 §4.6）。
 *
 * <p>在熔断降级 / 主 Agent 不可用时，从同角色 Agent 中选择替代者。
 * 自动跳过 SLEEPING、OFFLINE、熔断中的 Agent，优先选分数最高的可用 Agent。</p>
 *
 * @see ResilientDispatcher
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSelector {

    private final AgentService agentService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final AgentDispatchProperties agentDispatchProperties;
    private final AgentDutyLeaseService agentDutyLeaseService;

    /**
     * 从指定角色的 Agent 中选取首选执行器（用于初始分配）。
     *
     * <p>注意：本方法只负责“选人”，不落库、不发布事件。
     * 分配与熔断降级应由 {@link com.helloai.core.agent.dispatcher.ResilientDispatcher} 统一完成。</p>
     */
    public Agent pickPreferred(AgentRole role) {
        List<Agent> candidates;
        if (role != null) {
            candidates = agentService.listByRole(role);
        } else {
            candidates = agentService.listActive();
        }
        return pickFromCandidates(candidates, null);
    }

    /**
     * 从同角色 Agent 中选取替代者。
     *
     * <p>过滤规则（按优先级）：
     * <ol>
     *   <li>跳过 excludeAgentId（被熔断或不可用的原 Agent）</li>
     *   <li>跳过 SLEEPING 状态</li>
     *   <li>跳过 OFFLINE 状态</li>
     *   <li>跳过 status != ACTIVE（已禁用的 Agent）</li>
     *   <li>跳过熔断器已打开的 Agent（per-agent 维度）</li>
     *   <li>N12 P1 STRICT 独占报锁：跳过当前以 STRICT 模式在岗的 Agent
     *       （不参与他人失败后的替补池，但可被初始/直接分配的任务命中）</li>
     *   <li>按 score DESC 排序，选最高分</li>
     * </ol>
     *
     * @param excludeAgentId 需要排除的 Agent ID（原分配目标）
     * @param role           Agent 角色；为 null 时不限定角色
     * @return 可用替代 Agent，无可选时返回 null
     */
    public Agent pickAlternative(Long excludeAgentId, AgentRole role) {
        List<Agent> candidates;
        if (role != null) {
            candidates = agentService.listByRole(role);
        } else {
            candidates = agentService.listActive();
        }
        return pickFromCandidates(candidates, excludeAgentId);
    }

    private Agent pickFromCandidates(List<Agent> candidates, Long excludeAgentId) {
        return candidates.stream()
                .filter(a -> excludeAgentId == null || !a.getId().equals(excludeAgentId))
                .filter(a -> agentDispatchProperties.getForceAccessType() == null
                        || (a.getAccessType() != null && a.getAccessType() == agentDispatchProperties.getForceAccessType()))
                .filter(a -> a.getOnlineStatus() != AgentOnlineStatus.SLEEPING)
                .filter(a -> a.getOnlineStatus() != AgentOnlineStatus.OFFLINE
                        || (a.getAccessType() != null && !a.getAccessType().requiresRuntimeLiveness()))
                .filter(a -> !agentDispatchProperties.isRequireIdle() || agentService.inProgressCount(a.getId()) == 0)
                .filter(a -> a.getStatus() == AgentStatus.ACTIVE)
                .filter(a -> !isOnStrictDuty(a.getId()))
                .filter(this::isCircuitClosed)
                .max(resolveComparator())
                .orElse(null);
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
}
