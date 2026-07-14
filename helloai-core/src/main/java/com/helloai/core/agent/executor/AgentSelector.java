package com.helloai.core.agent.executor;

import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.entity.Agent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import com.helloai.core.service.AgentService;

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

        return candidates.stream()
                .filter(a -> !a.getId().equals(excludeAgentId))
                .filter(a -> a.getOnlineStatus() != AgentOnlineStatus.SLEEPING)
                .filter(a -> a.getOnlineStatus() != AgentOnlineStatus.OFFLINE
                        || (a.getAccessType() != null && !a.getAccessType().requiresRuntimeLiveness()))
                .filter(a -> a.getStatus() == AgentStatus.ACTIVE)
                .filter(this::isCircuitClosed)
                .max(Comparator.comparing(Agent::getScore, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
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
