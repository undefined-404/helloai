package com.helloai.core.agent.service;

/**
 * 熔断器状态变更报警服务（钉钉/飞书 webhook 推送，异步执行不阻塞调用方）。
 */
public interface CircuitBreakerAlertService {

    /**
     * 熔断器状态变更时发送报警。
     *
     * @param cbName    熔断器名称
     * @param agentId   关联 Agent ID
     * @param fromState 变更前状态
     * @param toState   变更后状态
     */
    void onCircuitStateChange(String cbName, Long agentId,
                              String fromState, String toState);
}
