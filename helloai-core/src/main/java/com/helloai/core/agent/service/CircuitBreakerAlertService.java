package com.helloai.core.agent.service;

/**
 * 平台运行告警服务（钉钉/飞书 webhook 推送，异步执行不阻塞调用方）。
 *
 * <p>原为「熔断器状态变更报警」，2026-10-21 扩展为通用运行告警：
 * 新增 {@link #onNoEligibleAgent}（调度无候选）——复用同一 webhook 通道与发送核心，
 * 避免为同类诉求再造一套发送链路（单点收口原则）。</p>
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

    /**
     * 调度「无可执行候选 Agent」告警（G-015 收口，2026-10-21）。
     *
     * <p>触发点：{@code SubTaskDispatchServiceImpl.doDispatchPendingAuto} 无候选分支
     * （此前只有 log + 时间线事件，无指标/告警出口）。该场景属<b>可自愈等待态</b>
     * （候选忙/离线缓解后由周期巡检自动重试，不消耗重派预算）——告警用于运维感知
     * 「长时间无 Agent 可接活」，而非视为业务故障。</p>
     *
     * @param subTaskId 无法派发的子任务 ID
     * @param taskId    所属任务 ID（可为 null）
     * @param role      期望的 Agent 角色
     * @param reason    无候选原因（如「无在线候选」「技能不匹配」）
     */
    void onNoEligibleAgent(Long subTaskId, Long taskId, String role, String reason);
}
