package com.helloai.core.agent.dispatcher;

import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import io.github.resilience4j.core.ConfigurationNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.observability.CircuitBreakerEventRecorder;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskService;

/**
 * 弹性调度器（v2.4 §4.5）。
 *
 * <p>为任务分配提供熔断降级保护：
 * <ul>
 *   <li>外层：@{@link CircuitBreaker}(name="agentDispatch") — 整体调度熔断</li>
 *   <li>内层：per-agentId 独立熔断器（agentDispatch-{agentId}），
 *       以 agentDispatch 实例配置为模板，实现按 Agent 维度熔断</li>
 *   <li>降级：熔断打开或执行失败时，通过 {@link AgentSelector#pickAlternative}
 *       在同角色 Agent 中选择替代者重新分配</li>
 * </ul>
 *
 * <p>熔断参数（application.yml）：
 * <ul>
 *   <li>failureRateThreshold=30</li>
 *   <li>waitDurationInOpenState=60s</li>
 *   <li>slidingWindowSize=10</li>
 * </ul>
 *
 * @see AgentSelector
 * @see SubTaskService#assignNext(Long, Long)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResilientDispatcher {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final CircuitBreakerEventRecorder circuitBreakerEventRecorder;
    private final SubTaskService subTaskService;
    private final AgentService agentService;
    private final AgentSelector agentSelector;

    private static final String DISPATCH_CB_NAME = "agentDispatch";

    /**
     * 弹性分配任务给指定 Agent。
     *
     * <p>执行流程：
     * <ol>
     *   <li>外层 @CircuitBreaker 保护整体调度</li>
     *   <li>按 agentId 获取/创建 per-agent 熔断器</li>
     *   <li>校验 Agent 在线状态（SLEEPING/OFFLINE 立即 fast-fail）</li>
     *   <li>调用 {@link SubTaskService#assignNext} 执行分配</li>
     *   <li>失败/熔断打开 → fallback 选取替代 Agent</li>
     * </ol>
     *
     * @param agentId   目标 Agent ID
     * @param subTaskId 待分配的子任务 ID
     */
    @CircuitBreaker(name = "agentDispatch", fallbackMethod = "assignNextFallback")
    public void assignNext(Long agentId, Long subTaskId) {
        // 按 agentId 维度获取独立熔断器（以 agentDispatch 配置为模板）
        io.github.resilience4j.circuitbreaker.CircuitBreaker perAgentCb = resolvePerAgentCircuitBreaker(agentId);

        // 注册审计监听（幂等，同一 cbName 只注册一次）
        circuitBreakerEventRecorder.registerListener(agentId, perAgentCb);

        perAgentCb.decorateRunnable(() -> {
            Agent agent = agentService.getById(agentId);
            if (agent == null) {
                throw new BizException("Agent 不存在: " + agentId);
            }

            // 快速失败：跳过明显不可用的 Agent（抛 AgentUnavailableException，不计入熔断统计）
            AgentOnlineStatus onlineStatus = agent.getOnlineStatus();
            if (onlineStatus == AgentOnlineStatus.SLEEPING) {
                throw new AgentUnavailableException("Agent 处于 SLEEPING 状态，不可分配: " + agentId, agentId);
            }
            AgentAccessType accessType = agent.getAccessType();
            if (onlineStatus == AgentOnlineStatus.OFFLINE
                    && (accessType == null || accessType.requiresRuntimeLiveness())) {
                throw new AgentUnavailableException("Agent 处于 OFFLINE 状态，不可分配: " + agentId, agentId);
            }

            log.info("弹性调度分配: agentId={}, subTaskId={}, onlineStatus={}",
                    agentId, subTaskId, onlineStatus);
            subTaskService.assignNext(agentId, subTaskId);
        }).run();
    }

    private io.github.resilience4j.circuitbreaker.CircuitBreaker resolvePerAgentCircuitBreaker(Long agentId) {
        String perAgentName = DISPATCH_CB_NAME + "-" + agentId;
        try {
            return circuitBreakerRegistry.circuitBreaker(perAgentName, DISPATCH_CB_NAME);
        } catch (ConfigurationNotFoundException e) {
            log.warn("熔断模板配置不存在，回退默认配置: template={}, perAgentName={}",
                    DISPATCH_CB_NAME, perAgentName);
            return circuitBreakerRegistry.circuitBreaker(perAgentName);
        }
    }

    /**
     * 熔断降级：选取替代 Agent 重新分配。
     *
     * <p>触发场景：
     * <ul>
     *   <li>熔断器 OPEN — 该 Agent 短期内故障率过高</li>
     *   <li>执行异常 — Agent 不可用或分配失败</li>
     * </ul>
     *
     * @param agentId   原始分配目标 Agent ID
     * @param subTaskId 待分配的子任务 ID
     * @param t         原始异常（CallNotPermittedException 或 BizException）
     */
    @SuppressWarnings("unused")
    private void assignNextFallback(Long agentId, Long subTaskId, Throwable t) {
        log.warn("调度降级触发: agentId={}, subTaskId={}, reason={}",
                agentId, subTaskId, t.getMessage());

        // 获取原 Agent 角色用于同角色替代
        Agent originalAgent = agentService.getById(agentId);
        AgentRole role = originalAgent != null ? originalAgent.getRole() : null;

        // 选取替代 Agent
        Agent alternative = agentSelector.pickAlternative(agentId, role);

        if (alternative == null) {
            String msg = String.format(
                    "无可用替代 Agent: excludeAgentId=%d, role=%s", agentId, role);
            log.error(msg);
            throw new BizException(msg);
        }

        log.info("熔断降级成功: originalAgentId={} → alternativeAgentId={}, subTaskId={}",
                agentId, alternative.getId(), subTaskId);
        subTaskService.assignNext(alternative.getId(), subTaskId);
    }
}
