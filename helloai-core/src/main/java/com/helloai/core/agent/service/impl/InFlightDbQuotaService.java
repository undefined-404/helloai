package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 并发额度默认实现：DB 实时统计（E2，一条线方案）。
 *
 * <p>占用 = sub_task 在飞数（ASSIGNED/IN_PROGRESS/REWORK），任务进入终态
 * （DONE/CANCELLED/DEAD_LETTER 等）后统计自动减少，无需显式释放——
 * 完成、改派、超时回收全部天然覆盖。</p>
 *
 * <p>额度解析优先级（见 {@link #resolveQuota}）：值班租约 maxConcurrent &gt;
 * capabilities.maxConcurrentTasks 显式值 &gt; null（不限制，与 E2 前行为一致）。</p>
 *
 * <p>原子防线不在本类（查询侧），而在 {@code SubTaskService.assignNext}
 * 的 agent 行锁 + 锁内判定（见 SubTaskServiceImpl），保证并发派发不超发。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InFlightDbQuotaService implements ConcurrencyQuotaService {

    /** capabilities 中的并发额度键（能力声明，无租约时生效）。 */
    public static final String MAX_CONCURRENT_CAPABILITY_KEY = "maxConcurrentTasks";

    // 懒解析打破循环：SubTaskServiceImpl 构造注入 ConcurrencyQuotaService（本类），
    // 本类再直接注入 SubTaskService/AgentService/AgentDutyLeaseService 会形成多条构造器环
    // （subTaskServiceImpl ↔ inFlightDbQuotaService、inFlightDbQuotaService → agentDutyLeaseServiceImpl
    // → subTaskServiceImpl、inFlightDbQuotaService → agentServiceImpl，§6.86 引入，重启暴露）
    private final ObjectProvider<SubTaskService> subTaskServiceProvider;
    private final ObjectProvider<AgentDutyLeaseService> agentDutyLeaseServiceProvider;
    private final ObjectProvider<AgentService> agentServiceProvider;

    @Override
    public int inFlightCount(Long agentId) {
        SubTaskService subTaskService = subTaskServiceProvider.getIfAvailable();
        if (subTaskService == null) {
            return 0;
        }
        return subTaskService.countInFlightByAgent(agentId);
    }

    @Override
    public Integer resolveQuota(Long agentId) {
        // 1. 值班租约声明优先：checkIn 显式承诺的容量
        AgentDutyLeaseService agentDutyLeaseService = agentDutyLeaseServiceProvider.getIfAvailable();
        AgentDutyLease lease = agentDutyLeaseService == null ? null : agentDutyLeaseService.getActiveLease(agentId);
        if (lease != null && lease.getMaxConcurrent() != null) {
            return lease.getMaxConcurrent();
        }
        // 2. 无租约：仅当 capabilities 显式声明 maxConcurrentTasks 才约束；
        //    未声明返回 null（不限制），保证与 E2 前行为完全兼容。
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            return null;
        }
        try {
            Agent agent = agentService.getById(agentId);
            if (agent == null || agent.getCapabilities() == null) {
                return null;
            }
            Object v = agent.getCapabilities().get(MAX_CONCURRENT_CAPABILITY_KEY);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {
                    log.debug("agent {} capabilities.maxConcurrentTasks 非数字: {}", agentId, s);
                }
            }
        } catch (Exception e) {
            // 防御式：额度解析异常降级为不限制，不阻断派发主链路
            log.debug("resolveQuota fallback to null for agent {}: {}", agentId, e.getMessage());
        }
        return null;
    }
}
