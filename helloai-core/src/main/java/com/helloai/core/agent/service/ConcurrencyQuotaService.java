package com.helloai.core.agent.service;

/**
 * Agent 并发额度服务（N12  第 3 段 E2：maxConcurrent 派发即占用）。
 *
 * <p>统一"选人前容量预检 + 派发落库前原子校验"的额度判定入口：
 * <ul>
 *   <li>额度来源：ACTIVE 值班租约的 {@code maxConcurrent}（checkIn 显式承诺）；
 *       无租约时仅当 capabilities 显式声明 {@code maxConcurrentTasks} 才约束（向后兼容）</li>
 *   <li>占用口径：ASSIGNED / IN_PROGRESS / REWORK（与 E1 租约在飞口径一致）</li>
 * </ul>
 * </p>
 *
 * <p>默认实现 {@code InFlightDbQuotaService} 以 DB 实时统计为事实源（一条线，
 * 完成/改派/回收天然释放）；企业版可替换为 Redis 预扣实现（独立事实源 + 对账），
 * 接口保持不变。</p>
 *
 * @see com.helloai.core.agent.service.impl.InFlightDbQuotaService
 * @see com.helloai.core.agent.executor.AgentSelector
 */
public interface ConcurrencyQuotaService {

    /**
     * 当前在飞占用数（ASSIGNED / IN_PROGRESS / REWORK，deleted = 0）。
     *
     * @param agentId Agent ID
     * @return 占用数（>= 0）
     */
    int inFlightCount(Long agentId);

    /**
     * 并发额度；null 表示未声明额度（不限制）。
     *
     * <p>优先级：ACTIVE 租约 maxConcurrent（值班承诺）&gt;
     * capabilities.maxConcurrentTasks 显式值（能力声明）&gt; null（不限制）。</p>
     *
     * @param agentId Agent ID
     * @return 额度；null = 不限制
     */
    Integer resolveQuota(Long agentId);

    /**
     * 是否可再接收一个任务：额度未声明（null）或当前占用 &lt; 额度。
     *
     * @param agentId Agent ID
     * @return true = 可接收
     */
    default boolean canAccept(Long agentId) {
        Integer quota = resolveQuota(agentId);
        return quota == null || inFlightCount(agentId) < quota;
    }
}
