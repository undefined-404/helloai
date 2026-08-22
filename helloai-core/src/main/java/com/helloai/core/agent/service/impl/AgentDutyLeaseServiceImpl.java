package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.AgentDutyLeaseProperties;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.entity.AgentDutyLeaseLatestRow;
import com.helloai.core.agent.mapper.AgentDutyLeaseMapper;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.shared.event.DutyLeaseClosedEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent 值班租约服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDutyLeaseServiceImpl extends ServiceImpl<AgentDutyLeaseMapper, AgentDutyLease>
        implements AgentDutyLeaseService {

    private final ApplicationEventPublisher eventPublisher;
    private final AgentMapper agentMapper;
    private final SubTaskService subTaskService;
    private final AgentDutyLeaseProperties dutyLeaseProperties;
    private final AgentDispatchProperties agentDispatchProperties;
    private final AgentQualityProfileService agentQualityProfileService;

    /**
     * 按 ID 批量查询 Agent 名称（用于值班租约报表面板填充 agentName）。
     *
     * <p>为避免 N+1，本方法走 {@code AgentMapper.selectBatchIds} 一次性查询。
     * 入参中的 null 元素会被跳过；返回 Map 键为 Agent ID，值为 Agent 名称（无记录的 ID 不在 Map 中）。</p>
     *
     * @param agentIds 待查询的 Agent ID 集合（可为 null 或空集合）
     * @return id → name 映射；输入为空时返回空 Map
     */
    @Override
    public Map<Long, String> getAgentNamesByIds(Collection<Long> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> idSet = agentIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (idSet.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Agent> agents = agentMapper.selectBatchIds(idSet);
        if (agents == null || agents.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new LinkedHashMap<>();
        for (Agent a : agents) {
            if (a.getId() != null && a.getName() != null) {
                result.put(a.getId(), a.getName());
            }
        }
        return result;
    }

    /**
     * 查询 Agent 当前有效的值班租约。
     *
     * @return null 如果当前没有 ACTIVE 租约
     */
    @Override
    public AgentDutyLease getActiveLease(Long agentId) {
        if (agentId == null) {
            return null;
        }
        return baseMapper.selectActiveByAgentId(agentId);
    }

    /**
     * 判断 Agent 当前是否处于值班态（有 ACTIVE 租约）。
     */
    @Override
    public boolean isOnDuty(Long agentId) {
        return getActiveLease(agentId) != null;
    }

    /**
     * 查询 Agent 最近一条值班租约（任意状态，按开始时间倒序取第一条）。
     *
     * <p>：checkOut 幂等返回当前状态时使用——租约已过期或从未打卡时
     * 也能给出可自检的语义（EXPIRED / NONE），而不是只给 closedCount=0。</p>
     *
     * @return null 如果该 Agent 从未有过租约
     */
    @Override
    public AgentDutyLease getLatestLease(Long agentId) {
        if (agentId == null) {
            return null;
        }
        return lambdaQuery()
                .eq(AgentDutyLease::getAgentId, agentId)
                .orderByDesc(AgentDutyLease::getStartTime)
                .last("LIMIT 1")
                .one();
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
    @Override
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
    @Override
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
            // 签退（checkOut）→ 事务提交后发布租约关闭事件（原门铃断连监听已随门铃通道搁置 ，事件保留供未来复用）
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
    @Override
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
     * 解析租约 TTL 窗口（E1 动态 TTL 自适应，N12  第 2 段）。
     *
     * <p>显式入参（checkIn 传了 ttlMinutes）永远优先；否则按 Agent 表现动态推断：
     * <ul>
     *   <li>有 {@code agent.score} → 线性映射 [0, fullScore] → [min, max]，
     *       低分 Agent 窗口短（快速回收），高分 Agent 窗口长（减少续约开销）</li>
     *   <li>无 score → 用 {@code consecutive_failure_count} 折算表现分（每次失败 -20，下限 0），
     *       连续失败越多窗口越短</li>
     *   <li>反馈回路第 1 层：performanceScore 升级为复合分 =
     *       失败折算分 + 质量分(0~100) × {@code helloai.dispatch.quality-weight}；
     *       质量画像缺失或权重为 0 时回退原逻辑（开关可回退）</li>
     *   <li>自适应开关关闭 / agentId 为空 / Agent 记录不存在 → defaultTtlMinutes 兜底</li>
     * </ul></p>
     *
     * @param agentId             Agent ID
     * @param explicitTtlMinutes  checkIn 显式传入的 TTL（分钟）；null 或 &lt;=0 表示走动态推断
     * @return 解析后的租约窗口（分钟），恒 &gt; 0
     */
    @Override
    public int resolveTtlMinutes(Long agentId, Integer explicitTtlMinutes) {
        if (explicitTtlMinutes != null && explicitTtlMinutes > 0) {
            return explicitTtlMinutes;
        }
        if (agentId == null || !dutyLeaseProperties.isAdaptiveTtlEnabled()) {
            return dutyLeaseProperties.getDefaultTtlMinutes();
        }
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            return dutyLeaseProperties.getDefaultTtlMinutes();
        }
        int fullScore = Math.max(dutyLeaseProperties.getFullScore(), 1);
        int performanceScore;
        if (agent.getScore() != null) {
            performanceScore = agent.getScore();
        } else {
            int failures = agent.getConsecutiveFailureCount() != null ? agent.getConsecutiveFailureCount() : 0;
            performanceScore = fullScore - failures * 20;
        }
        performanceScore = Math.max(0, Math.min(fullScore, performanceScore));
        // 反馈回路第 1 层：失败折算分 + 质量分加权 → 复合分（同源 quality-weight 配置；
        // 权重 0 或质量分缺失时保持原逻辑，开关可回退）
        double qualityWeight = agentDispatchProperties.getQualityWeight();
        if (qualityWeight > 0) {
            try {
                Integer qualityScore = agentQualityProfileService.computeQualityScore(agentId);
                if (qualityScore != null) {
                    double composite = performanceScore + qualityScore * qualityWeight;
                    performanceScore = (int) Math.round(Math.max(0, Math.min(fullScore, composite)));
                }
            } catch (Exception e) {
                // 防御式：画像查询异常回退失败折算分，不阻断 checkIn 链路
                log.debug("复合分画像查询异常（回退原逻辑）: agentId={}, err={}", agentId, e.getMessage());
            }
        }
        int min = Math.max(dutyLeaseProperties.getMinTtlMinutes(), 1);
        int max = Math.max(dutyLeaseProperties.getMaxTtlMinutes(), min);
        return min + (max - min) * performanceScore / fullScore;
    }

    /**
     * 自适应续约（E1 动态 TTL 自适应）：按 Agent 当前状态动态计算续约窗口。
     *
     * <p>有在跑子任务（ASSIGNED / IN_PROGRESS / REWORK）→ 用最大窗口，任务执行期稳定保活；
     * 空闲 → 按表现分动态窗口（低分短、高分长）。无 ACTIVE 租约时返回 null，
     * 不自动打卡（保持 checkIn 的打卡语义）。供工具调用自动续租路径
     * （{@code McpToolService.refreshDutyLease}）使用。</p>
     *
     * @param agentId Agent ID
     * @return 续约后的租约；当前无 ACTIVE 租约返回 null
     */
    @Override
    public AgentDutyLease adaptiveRenew(Long agentId) {
        AgentDutyLease active = getActiveLease(agentId);
        if (active == null) {
            return null;
        }
        int ttlMinutes = hasInFlightSubTask(agentId)
                ? Math.max(dutyLeaseProperties.getMaxTtlMinutes(), dutyLeaseProperties.getMinTtlMinutes())
                : resolveTtlMinutes(agentId, null);
        return renewLease(agentId, ttlMinutes);
    }

    /**
     * 判断 Agent 是否存在在跑子任务（ASSIGNED / IN_PROGRESS / REWORK）。
     *
     * <p>复用 {@code SubTaskService.selectInFlightByAgent} 的在跑语义；
     * REVIEW（审核中）与 DONE 等状态视为已交付，不再计入执行期保活。</p>
     */
    private boolean hasInFlightSubTask(Long agentId) {
        if (agentId == null) {
            return false;
        }
        List<SubTask> inFlight = subTaskService.selectInFlightByAgent(agentId, 1);
        return inFlight != null && !inFlight.isEmpty();
    }

    /**
     * 扫描到期的 ACTIVE 租约并批量翻为 EXPIRED（AgentHub P0-C）。
     *
     * <p>由 helloai-job 中的 {@code DutyLeaseExpirationTask} 周期性调用。
     * 每个 Agent 的翻转单独一条 UPDATE，沿用 {@link #closeLease} 相同的
     * 原子条件更新 SQL，仅将 status 从 'ACTIVE' 改为 'EXPIRED'，close_reason 为 'lease_expired'。</p>
     *
     * <p>安全兵：若同一个 Agent 的旧 lease 已被 checkIn 新建时关闭、新建不到期，
     * closeActiveLeases 也只会影响新的 ACTIVE 行（旧行已非 ACTIVE）；但因为新行不在
     * selectExpiredLeases 结果中，不会被作为"到期"代入本方法。</p>
     *
     * @param batchLimit 单轮扫描上限，建议 100～500
     * @return 成功翻为 EXPIRED 的行数
     */
    @Override
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
                // 租约到期 → 事务提交后发布租约关闭事件（与 checkOut 同一条事件路径）
                eventPublisher.publishEvent(new DutyLeaseClosedEvent(lease.getAgentId(), "lease_expired"));
            }
        }
        return total;
    }

    /**
     * 分页查询值班租约（AgentHub P1 值班报表数据源，只读）。
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
    @Override
    public IPage<AgentDutyLease> listLeases(Long agentId, AgentDutyLeaseStatus status,
                                            long pageNum, long pageSize) {
        LambdaQueryWrapper<AgentDutyLease> wrapper = new LambdaQueryWrapper<AgentDutyLease>()
                .eq(agentId != null, AgentDutyLease::getAgentId, agentId)
                .eq(status != null, AgentDutyLease::getStatus, status)
                .orderByDesc(AgentDutyLease::getStartTime);
        return page(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * Agent 维度分页：每个 Agent 只返回最新一条租约 + 该 Agent 租约总数（只读）。
     *
     * <p>total 为有租约记录的 Agent 数；排序按最新租约开始时间倒序
     * （最近上班的 Agent 在前）。分组取最新走 Mapper 自定义 DISTINCT ON SQL，
     * 非 MyBatis-Plus 分页插件链路，故手工拼 Page。</p>
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页 Agent 数
     * @return 分页结果，绝不返回 null
     */
    @Override
    public IPage<AgentDutyLeaseLatestRow> listLatestPerAgent(long pageNum, long pageSize) {
        long safePageNum = Math.max(pageNum, 1);
        long safePageSize = Math.max(pageSize, 1);
        long total = baseMapper.countDistinctAgents();
        Page<AgentDutyLeaseLatestRow> page = new Page<>(safePageNum, safePageSize, total);
        if (total == 0) {
            page.setRecords(Collections.emptyList());
            return page;
        }
        page.setRecords(baseMapper.selectLatestPerAgent((safePageNum - 1) * safePageSize, safePageSize));
        return page;
    }

    /**
     * 今日打卡概览：按 Agent 维度统计各状态的 Agent 数（只读）。
     *
     * <p>每个 Agent 只按其最新一条租约的状态计一次（要么在线、要么下班、
     * 要么超时），而非历史租约条数累计。口径：今日有打卡记录或当前仍
     * ACTIVE 在线（含昨日打卡至今未下班）的 Agent。缺失状态补 0，保证
     * 返回的键始终齐全（ACTIVE / CLOSED / EXPIRED），供看板卡片直接消费。</p>
     *
     * @return 状态 → Agent 数（顺序稳定），绝不返回 null
     */
    @Override
    public Map<AgentDutyLeaseStatus, Long> countTodayAgentsByStatus() {
        OffsetDateTime todayStart = OffsetDateTime.now()
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
        List<String> statuses = baseMapper.selectTodayLatestStatusPerAgent(todayStart);

        Map<AgentDutyLeaseStatus, Long> counts = new LinkedHashMap<>();
        for (AgentDutyLeaseStatus s : AgentDutyLeaseStatus.values()) {
            counts.put(s, 0L);
        }
        for (String status : statuses) {
            for (AgentDutyLeaseStatus s : AgentDutyLeaseStatus.values()) {
                if (s.name().equals(status)) {
                    counts.merge(s, 1L, Long::sum);
                    break;
                }
            }
        }
        return counts;
    }
}
