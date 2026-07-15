package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.core.entity.AgentDutyLease;
import com.helloai.core.mapper.AgentDutyLeaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Agent 值班租约服务。
 *
 * <p>AgentHub V1 T3 最小骨架，提供值班态事实源的 CRUD。</p>
 *
 * <p>本轮只做：
 * <ul>
 *   <li>查询当前有效 lease</li>
 *   <li>关闭旧 lease（签退 / 强制关闭）</li>
 *   <li>新建 lease（打卡上班）</li>
 * </ul>
 * 本轮不做：checkIn/checkOut、selector 接入、dashboard。</p>
 */
@Slf4j
@Service
public class AgentDutyLeaseService extends ServiceImpl<AgentDutyLeaseMapper, AgentDutyLease> {

    /**
     * 查询 Agent 当前有效的值班租约。
     *
     * @return null 如果当前没有 ACTIVE 租约
     */
    public AgentDutyLease getActiveLease(Long agentId) {
        if (agentId == null) {
            return null;
        }
        return baseMapper.selectActiveByAgentId(agentId);
    }

    /**
     * 判断 Agent 当前是否处于值班态（有 ACTIVE 租约）。
     */
    public boolean isOnDuty(Long agentId) {
        return getActiveLease(agentId) != null;
    }

    /**
     * 为 Agent 开启新的值班租约（打卡上班）。
     *
     * <p>事务内先关闭该 Agent 的所有旧 ACTIVE 租约（防御性），再新建一条。</p>
     *
     * @param agentId       Agent ID
     * @param workMode      工作模式
     * @param maxConcurrent 最大并发数
     * @param ttlMinutes    租约有效期（分钟）
     * @return 新建的租约
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentDutyLease startLease(Long agentId, String workMode,
                                     Integer maxConcurrent, int ttlMinutes) {
        if (agentId == null) {
            throw new BizException("agentId 不能为空");
        }

        // 防御性关闭旧租约
        int closed = baseMapper.closeActiveLeases(
                agentId,
                AgentDutyLeaseStatus.CLOSED.name(),
                "new_lease_start",
                OffsetDateTime.now());
        if (closed > 0) {
            log.info("关闭 Agent {} 的旧租约 {} 条", agentId, closed);
        }

        OffsetDateTime now = OffsetDateTime.now();
        AgentDutyLease lease = new AgentDutyLease();
        lease.setAgentId(agentId);
        lease.setSessionId(UUID.randomUUID().toString());
        lease.setWorkMode(workMode);
        lease.setMaxConcurrent(maxConcurrent != null ? maxConcurrent : 1);
        lease.setStatus(AgentDutyLeaseStatus.ACTIVE);
        lease.setStartedAt(now);
        lease.setLastRenewedAt(now);
        lease.setExpiresAt(now.plusMinutes(ttlMinutes));
        save(lease);

        log.info("Agent {} 值班租约已创建: sessionId={}, expiresAt={}",
                agentId, lease.getSessionId(), lease.getExpiresAt());
        return lease;
    }

    /**
     * 关闭 Agent 当前有效的值班租约（签退）。
     *
     * @param agentId     Agent ID
     * @param closeReason 关闭原因
     * @return 关闭的租约条数（0 = 没有需要关闭的 ACTIVE 租约）
     */
    @Transactional(rollbackFor = Exception.class)
    public int closeLease(Long agentId, String closeReason) {
        if (agentId == null) {
            return 0;
        }
        String reason = closeReason != null ? closeReason : "manual_close";
        int closed = baseMapper.closeActiveLeases(
                agentId,
                AgentDutyLeaseStatus.CLOSED.name(),
                reason,
                OffsetDateTime.now());
        if (closed > 0) {
            log.info("Agent {} 值班租约已关闭: reason={}, affected={}", agentId, reason, closed);
        }
        return closed;
    }

    /**
     * 续约：延长当前 ACTIVE 租约的 expires_at 和 last_renewed_at。
     *
     * <p>按 agentId 精确指定，仅续约最新一条 ACTIVE 租约。
     * 如果 Agent 当前无 ACTIVE 租约，返回 null。</p>
     *
     * @param agentId    Agent ID
     * @param ttlMinutes 续约时长（分钟）
     * @return 续约后的租约；如果无 ACTIVE 租约则返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentDutyLease renewLease(Long agentId, int ttlMinutes) {
        AgentDutyLease active = getActiveLease(agentId);
        if (active == null) {
            log.warn("Agent {} 续约失败：当前无 ACTIVE 租约", agentId);
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now();
        active.setLastRenewedAt(now);
        active.setExpiresAt(now.plusMinutes(ttlMinutes));
        updateById(active);
        log.info("Agent {} 值班租约已续约: expiresAt={}", agentId, active.getExpiresAt());
        return active;
    }
}
