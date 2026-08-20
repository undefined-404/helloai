package com.helloai.common.base;

/**
 * Agent 不可用异常（§4.5 熔断 fast-fail）。
 *
 * <p>与 {@link BizException} 的区别：
 * <ul>
 *   <li>BizException — 业务逻辑异常，应计入熔断统计</li>
 *   <li>AgentUnavailableException — Agent 状态不可用（SLEEPING/OFFLINE/DISABLED），
 *       属于预期内的降级场景，<b>不应计入熔断失败率</b></li>
 * </ul>
 *
 * <p>使用场景：
 * <ul>
 *   <li>{@code ResilientDispatcher} 检测到目标 Agent 处于 SLEEPING/OFFLINE 时快速抛出</li>
 *   <li>{@code AgentSelector.pickAlternative()} 检测到所有候选都不可用时抛出</li>
 * </ul>
 *
 * <p>在 Resilience4j 配置中通过 {@code ignore-exceptions} 排除，避免污染熔断统计。</p>
 *
 * @see com.helloai.core.agent.dispatcher.ResilientDispatcher
 * @see com.helloai.core.agent.executor.AgentSelector
 */
public class AgentUnavailableException extends RuntimeException {

    private final Long agentId;

    public AgentUnavailableException(String message) {
        super(message);
        this.agentId = null;
    }

    public AgentUnavailableException(String message, Long agentId) {
        super(message);
        this.agentId = agentId;
    }

    public Long getAgentId() {
        return agentId;
    }
}
