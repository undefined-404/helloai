package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.shared.event.DutyLeaseClosedEvent;
import com.helloai.core.agent.mapper.AgentDutyLeaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
@RequiredArgsConstructor
public class AgentDutyLeaseService extends ServiceImpl<AgentDutyLeaseMapper, AgentDutyLease> {

    private final ApplicationEventPublisher eventPublisher;

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
        lease.setStartTime(now);
        lease.setLastRenewTime(now);
        lease.setExpireTime(now.plusMinutes(ttlMinutes));
        save(lease);

        log.info("Agent {} 值班租约已创建: sessionId={}, expiresAt={}",
                agentId, lease.getSessionId(), lease.getExpireTime());
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
            // 签退（checkOut）→ 事务提交后主动断门铃（离岗即挂电话，设计 §6.4）
            eventPublisher.publishEvent(new DutyLeaseClosedEvent(agentId, reason));
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
        active.setLastRenewTime(now);
        active.setExpireTime(now.plusMinutes(ttlMinutes));
        updateById(active);
        log.info("Agent {} 值班租约已续约: expiresAt={}", agentId, active.getExpireTime());
        return active;
    }

    /**
     * 扫描到期的 ACTIVE 租约并批量翻为 EXPIRED（AgentHub V1 P0-C）。
     *
     * <p>由 helloai-job 中的 {@code DutyLeaseExpirationTask} 周期性调用。
     * 每个 Agent 的翻转单独一条 UPDATE，沿用 {@link #closeLease} 相同的
     * 原子条件更新 SQL，仅将 status 从 'ACTIVE' 改为 'EXPIRED'，close_reason 为 'lease_expired'。</p>
     *
     * <p>安全兵：若同一个 Agent 的旧 lease 已被 checkIn 新建时关闭、新建不到期，
     * closeActiveLeases 也只会影响新的 ACTIVE 行（旧行已非 ACTIVE）；但因为新行不在
     * selectExpiredLeases 结果中，不会被作为“到期”代入本方法。</p>
     *
     * @param batchLimit 单轮扫描上限，建议 100～500
     * @return 成功翻为 EXPIRED 的行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int expireLeases(int batchLimit) {
        int limit = batchLimit > 0 ? batchLimit : 100;
        OffsetDateTime now = OffsetDateTime.now();
        List<AgentDutyLease> expired = baseMapper.selectExpiredLeases(now, limit);
        if (expired == null || expired.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (AgentDutyLease lease : expired) {
            int rows = baseMapper.closeActiveLeases(
                    lease.getAgentId(),
                    AgentDutyLeaseStatus.EXPIRED.name(),
                    "lease_expired",
                    now);
            if (rows > 0) {
                total += rows;
                log.info("值班租约到期已翻为 EXPIRED: agentId={}, leaseId={}, expiresAt={}",
                        lease.getAgentId(), lease.getId(), lease.getExpireTime());
                // 租约到期 → 事务提交后主动断门铃（与 checkOut 同一条断连路径）
                eventPublisher.publishEvent(new DutyLeaseClosedEvent(lease.getAgentId(), "lease_expired"));
            }
        }
        return total;
    }

    /**
     * 分页查询值班租约（AgentHub V1 P1 值班报表数据源，只读）。
     *
     * <p>为运营看板提供值班租约列表，支持按 Agent、状态过滤，
     * 按值班开始时间倒序（最近上班的在前）。逻辑删除由 {@code @TableLogic} 自动过滤。</p>
     *
     * @param agentId  可选，按 Agent 过滤；null 表示不限
     * @param status   可选，按租约状态过滤；null 表示不限
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页结果，绝不返回 null
     */
    public IPage<AgentDutyLease> listLeases(Long agentId, AgentDutyLeaseStatus status,
                                            long pageNum, long pageSize) {
        LambdaQueryWrapper<AgentDutyLease> wrapper = new LambdaQueryWrapper<AgentDutyLease>()
                .eq(agentId != null, AgentDutyLease::getAgentId, agentId)
                .eq(status != null, AgentDutyLease::getStatus, status)
                .orderByDesc(AgentDutyLease::getStartTime);
        return page(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 按状态统计值班租约条数（AgentHub V1 P1 值班报表数据源，只读）。
     *
     * <p>遍历所有状态并计数，缺失状态补 0，保证返回的键始终齐全
     * （ACTIVE / CLOSED / EXPIRED），供看板状态分布卡片直接消费。</p>
     *
     * @return 状态 → 条数（顺序稳定），绝不返回 null
     */
    public Map<AgentDutyLeaseStatus, Long> countByStatus() {
        Map<AgentDutyLeaseStatus, Long> counts = new LinkedHashMap<>();
        for (AgentDutyLeaseStatus s : AgentDutyLeaseStatus.values()) {
            long c = count(new LambdaQueryWrapper<AgentDutyLease>()
                    .eq(AgentDutyLease::getStatus, s));
            counts.put(s, c);
        }
        return counts;
    }
}
