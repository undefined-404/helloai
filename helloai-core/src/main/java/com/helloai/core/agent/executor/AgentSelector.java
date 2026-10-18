package com.helloai.core.agent.executor;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.AgentHealthProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.WorkMode;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.SkillNormalizer;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.projection.AgentProfileSnapshotProjection;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 选择器。
 *
 * <p>在熔断降级 / 主 Agent 不可用时，从同角色 Agent 中选择替代者。
 * 自动跳过 SLEEPING、OFFLINE、熔断中的 Agent，优先选分数最高的可用 Agent。</p>
 *
 * @see com.helloai.core.agent.dispatcher.ResilientDispatcher
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSelector {

    private final AgentService agentService;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final AgentDispatchProperties agentDispatchProperties;
    private final AgentHealthProperties agentHealthProperties;
    private final AgentDutyLeaseService agentDutyLeaseService;
    private final AgentLlmCredentialResolver agentLlmCredentialResolver;
    private final ConcurrencyQuotaService concurrencyQuotaService;
    private final AgentQualityProfileService agentQualityProfileService;
    private final AgentExecutionRecordService agentExecutionRecordService;

    /** 成本档位下界（最贵，有成本数据）。 */
    private static final int COST_RANK_MIN = 1;

    /** 成本档位上界（最省，有成本数据）。 */
    private static final int COST_RANK_MAX = 5;

    /** 成本档位中立值：候选内成本无区分度（min == max，含仅 1 个有样本）时取之，不偏袒。 */
    private static final int COST_RANK_NEUTRAL = 3;

    /**
     * 从指定角色的 Agent 中选取首选执行器（用于初始分配）。
     *
     * <p>注意：本方法只负责“选人”，不落库、不发布事件。
     * 分配与熔断降级应由 {@link com.helloai.core.agent.dispatcher.ResilientDispatcher} 统一完成。</p>
     */
    public Agent pickPreferred(AgentRole role) {
        return pickPreferred(role, null);
    }

    /**
     * 供跨域只读消费方使用的首选画像快照（RM5 批 2）。
     *
     * <p>与 {@link #pickPreferred(AgentRole)} 同口径选人，仅把返回的实体投影为
     * {@link AgentProfileSnapshot}——消费方（如 {@code ReviewerPickerImpl}）无需 import
     * {@code agent.entity.Agent}。选人语义、可用性过滤、排序<b>逐字不变</b>。</p>
     *
     * @param role Agent 角色；为 null 时不限定角色
     * @return 首选 Agent 画像快照；无可选时返回 {@code null}
     */
    public AgentProfileSnapshot pickPreferredProfile(AgentRole role) {
        return AgentProfileSnapshotProjection.toSnapshot(pickPreferred(role));
    }

    /**
     * （§6.58 P1）：带任务级约束的首选选人。
     *
     * <p>在 {@link #pickPreferred(AgentRole)} 基础上追加
     * {@link AgentSelectionConstraints} 过滤（执行者白名单 + 技能 AND 匹配），
     * 供任务指定 executorAgentIds / required_skills 时约束初始分配。</p>
     *
     * @param role        Agent 角色；为 null 时不限定角色
     * @param constraints 任务级选人约束；null 表示不约束（与旧行为一致）
     * @return 首选 Agent，无可选时返回 null
     */
    public Agent pickPreferred(AgentRole role, AgentSelectionConstraints constraints) {
        List<Agent> candidates;
        if (role != null) {
            candidates = agentService.listByRole(role);
        } else {
            candidates = agentService.listActive();
        }
        return pickFromCandidates(candidates, null, constraints);
    }

    /**
     * 从同角色 Agent 中选取替代者。
     *
     * <p>过滤规则（按优先级）：
     * <ol>
     *   <li>跳过 excludeAgentId（被熔断或不可用的原 Agent）</li>
     *   <li>跳过不满足任务级约束的 Agent（{@link AgentSelectionConstraints}——
     *       执行者白名单外、或未声明 required_skills 全部技能的 Agent）</li>
     *   <li>跳过 SLEEPING 状态</li>
     *   <li>跳过 OFFLINE 状态（由 markOfflineIfStale + Reconcile保证唯一性*       API_KEY_LLM 豁免）</li>
     *   <li>§4.1：心跳新鲜度过滤—— last_seen_time 距今超过
     *       {@link AgentHealthProperties#getOfflineMinutes()}（默认 5 分钟，
     *       对齐 Redis 心跳 TTL）的 Agent 被跳过，即使 online_status 仍是 ONLINE；
     *       防止选人拿到“刚被死但还未来得及被 Reconcile 标 OFFLINE”的 Agent。
     *       API_KEY_LLM 始终视为新鲜（不需要运行时心跳）。</li>
     *   <li>跳过 status != ACTIVE（已禁用的 Agent）</li>
     *   <li>跳过无启用态托管凭证的 API_KEY_LLM Agent（封堵双重豁免下的无凭证劫持）</li>
     *   <li>跳过熔断器已打开的 Agent（per-agent 维度）</li>
     *   <li>N12 P1 STRICT 独占报锁：跳过当前以 STRICT 模式在岗的 Agent
     *       （不参与他人失败后的替补池，但可被初始/直接分配的任务命中）</li>
     *   <li>E2：跳过并发额度已满的 Agent（当前占用 &gt;= 声明额度时不再接收新任务；
     *       {@code enforceMaxConcurrent=false} 时跳过本检查，与 E2 前行为一致）</li>
     *   <li>按 score DESC 排序，选最高分；前置软优先级为
     *       dutyRank →（preferExternal 时）accessTypeRank → qualityRank×qualityWeight
     *       → costRank×costWeight（B5.3 Fleet 成本选人，见 {@link #resolveCostRanks}）</li>
     * </ol>
     *
     * <p><b>过滤 vs 排序的分界</b>：任务级技能约束（{@code required_skills} AND 匹配）
     * 属<b>硬过滤</b>（决定能否进池），<b>不</b>参与排序打分；质量与成本属<b>软排序维度</b>。</p>
     *
     * @param excludeAgentId 需要排除的 Agent ID（原分配目标）
     * @param role           Agent 角色；为 null 时不限定角色
     * @return 可用替代 Agent，无可选时返回 null
     */
    public Agent pickAlternative(Long excludeAgentId, AgentRole role) {
        return pickAlternative(excludeAgentId, role, null);
    }

    /**
     * （§6.58 P1）：带任务级约束的替代选人。
     *
     * <p>在 {@link #pickAlternative(Long, AgentRole)} 基础上追加
     * {@link AgentSelectionConstraints} 过滤（执行者白名单 + 技能 AND 匹配），
     * 供任务指定 executorAgentIds / required_skills 时约束重分配链（含熔断降级替代）。</p>
     *
     * @param excludeAgentId 需要排除的 Agent ID（原分配目标）
     * @param role           Agent 角色；为 null 时不限定角色
     * @param constraints    任务级选人约束；null 表示不约束（与旧行为一致）
     * @return 可用替代 Agent，无可选时返回 null
     */
    public Agent pickAlternative(Long excludeAgentId, AgentRole role, AgentSelectionConstraints constraints) {
        List<Agent> candidates;
        if (role != null) {
            candidates = agentService.listByRole(role);
        } else {
            candidates = agentService.listActive();
        }
        return pickFromCandidates(candidates, excludeAgentId, constraints);
    }

    private Agent pickFromCandidates(List<Agent> candidates, Long excludeAgentId,
                                     AgentSelectionConstraints constraints) {
        Map<Long, Integer> qualityRanks = resolveQualityRanks(candidates);
        Map<Long, Integer> costRanks = resolveCostRanks(candidates);
        Map<Long, Integer> inFlight = resolveInFlightCounts(candidates);
        List<Agent> pool = candidates.stream()
                .filter(a -> excludeAgentId == null || !a.getId().equals(excludeAgentId))
                .filter(a -> constraints == null || constraints.allows(a))
                .filter(a -> agentDispatchProperties.getForceAccessType() == null
                        || (a.getAccessType() != null && a.getAccessType() == agentDispatchProperties.getForceAccessType()))
                .filter(a -> a.getOnlineStatus() != AgentOnlineStatus.SLEEPING)
                .filter(a -> a.getOnlineStatus() != AgentOnlineStatus.OFFLINE
                        || (a.getAccessType() != null && !a.getAccessType().requiresRuntimeLiveness()))
                .filter(this::isHeartbeatFresh)
                .filter(a -> !agentDispatchProperties.isRequireIdle() || agentService.inProgressCount(a.getId()) == 0)
                .filter(a -> !agentDispatchProperties.isEnforceMaxConcurrent()
                        || concurrencyQuotaService.canAccept(a.getId()))
                .filter(a -> a.getStatus() == AgentStatus.ACTIVE)
                .filter(this::hasUsableCredential)
                .filter(a -> !isOnStrictDuty(a.getId()))
                .filter(this::isCircuitClosed)
                .toList();
        if (pool.isEmpty()) {
            return null;
        }
        if (!isWeightedRandom()) {
            // 原路径逐字不变：分类优先级 + score 降序取最高
            return pool.stream()
                    .max(resolveComparator(qualityRanks, costRanks))
                    .orElse(null);
        }
        // 加权随机：先分「最优层级」（值班/接入类型/质量/成本 全等），再在层内抽签。
        // 分层不可跳过——否则会把低质量档的 Agent 抽上来，违反既有分类优先级语义。
        Comparator<Agent> tierComparator = resolveTierComparator(qualityRanks, costRanks);
        Agent tierBest = pool.stream().max(tierComparator).orElse(null);
        if (tierBest == null) {
            return null;
        }
        List<Agent> tier = pool.stream()
                .filter(a -> tierComparator.compare(a, tierBest) == 0)
                .toList();
        Agent chosen = weightedDraw(tier, inFlight, scoreRange(pool));
        if (chosen != null) {
            // 可观测（2026-10-18）：不记这行，切换策略后无法证明它生效，也无法核对权重与在飞读数
            log.info("选人策略 WEIGHTED_RANDOM: agentId={}, weight={}, inFlight={}, score={}, tierSize={}",
                    chosen.getId(), weightOf(chosen, scoreRange(pool)),
                    inFlight.getOrDefault(chosen.getId(), 0), chosen.getScore(), tier.size());
        }
        return chosen;
    }

    /** 是否走加权随机策略。 */
    private boolean isWeightedRandom() {
        return agentDispatchProperties.getSelectionStrategy()
                == AgentDispatchProperties.SelectionStrategy.WEIGHTED_RANDOM;
    }

    /**
     * 加权抽签：{@code P(agent) ∝ 权重(score) / (1 + 在飞数)}。
     *
     * <p><b>无状态设计</b>：不依赖 Redis 计数器或 JVM 内 currentWeight，故分布式多实例下
     * 分配比例依然正确（V1→V2「禁单实例假设」）。代价是短窗口内可能不均，长期公平。</p>
     *
     * <p>权重全为 0 / 空列表时退化为均匀随机（防御：绝不返回 null 当池非空）。</p>
     */
    private Agent weightedDraw(List<Agent> tier, Map<Long, Integer> inFlight, double[] scoreRange) {
        if (tier.isEmpty()) {
            return null;
        }
        double[] weights = new double[tier.size()];
        double total = 0.0;
        for (int i = 0; i < tier.size(); i++) {
            weights[i] = effectiveWeight(tier.get(i), scoreRange, inFlight);
            total += weights[i];
        }
        if (total <= 0.0) {
            return tier.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(tier.size()));
        }
        double r = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            r -= weights[i];
            if (r <= 0.0) {
                return tier.get(i);
            }
        }
        return tier.get(tier.size() - 1);
    }

    /** 有效权重：{@code 权重(score) / (1 + 在飞数)}。 */
    private double effectiveWeight(Agent agent, double[] range, Map<Long, Integer> inFlight) {
        int load = agent != null && agent.getId() != null
                ? inFlight.getOrDefault(agent.getId(), 0) : 0;
        return weightOf(agent, range) / (load + 1.0);
    }

    /**
     * 候选内预计算「在飞任务数」（{@code sub_task.status=IN_PROGRESS} 计数）。
     *
     * <p>一次选人只查一轮：抽签前需要权重，逐次查库会放大 DB 压力。
     * 注意：{@code require-idle=true} 时池内该值恒为 0（非空闲已被过滤）——
     * 因此公平性主要来自权重抽签，负载项只在并发开启时起作用。</p>
     */
    private Map<Long, Integer> resolveInFlightCounts(List<Agent> candidates) {
        if (!isWeightedRandom()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (Agent a : candidates) {
            if (a == null || a.getId() == null) {
                continue;
            }
            try {
                counts.put(a.getId(), Math.max(0, agentService.inProgressCount(a.getId())));
            } catch (Exception e) {
                log.debug("在飞任务数查询异常，按 0 处理: agentId={}, err={}", a.getId(), e.getMessage());
                counts.put(a.getId(), 0);
            }
        }
        return counts;
    }

    /** 候选内 score 的 [min, max]（权重归一用；全部无 score 时返回 null ⇒ 权重取中值）。 */
    private double[] scoreRange(List<Agent> candidates) {
        Integer min = null;
        Integer max = null;
        for (Agent a : candidates) {
            Integer sc = a != null ? a.getScore() : null;
            if (sc == null) {
                continue;
            }
            min = min == null ? sc : Math.min(min, sc);
            max = max == null ? sc : Math.max(max, sc);
        }
        return min == null ? null : new double[]{min, max};
    }

    /**
     * score → 权重（{@code [weightMin, weightMax]} 线性映射）。
     *
     * <p>无 score / 区间退化（全部同分）时取<b>中值权重</b>：此时退化为均匀随机，
     * 正好实现「同分候选机会均等」，不会被 ID 顺序固定垄断。</p>
     */
    private double weightOf(Agent agent, double[] range) {
        double lo = agentDispatchProperties.getWeightMin();
        double hi = agentDispatchProperties.getWeightMax();
        if (hi < lo) {
            double t = lo;
            lo = hi;
            hi = t;
        }
        if (lo <= 0.0 && hi <= 0.0) {
            return 1.0;   // 配置极端（全 0）：退回等权，避免 total=0 分支
        }
        Integer sc = agent != null ? agent.getScore() : null;
        if (sc == null || range == null || range[1] <= range[0]) {
            return (lo + hi) / 2.0;
        }
        double ratio = (sc - range[0]) / (range[1] - range[0]);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        return lo + (hi - lo) * ratio;
    }


    /**
     * 心跳新鲜度检查。
     *
     * <p>返回 true 表示 Agent 近期可见、参与选人：</p>
     * <ul>
     *   <li>API_KEY_LLM 类型 始终视为新鲜（requiresRuntimeLiveness=false，
     *       架构 §3.8 三层可用性）</li>
     *   <li>CLI_CLIENT：last_seen_time 距今 ≤
     *       {@link AgentHealthProperties#getOfflineMinutes()}（默认 5 分钟，
     *       对齐 Redis TTL）视为新鲜；last_seen_time=null 也视为陈旧</li>
     *   <li>阈值 ≤ 0 时视为关闭过滤（不推荐生产使用）</li>
     * </ul>
     *
     * <p>防御式：不因本检查本身报错而影响选人（如 last_seen_time 为 null
     * 造成 NPE 会被 try/catch 降级为不新鲜）。</p>
     *
     * <p>改为 public 供 {@link com.helloai.core.agent.dispatcher.ResilientDispatcher}
     * 在 fast-fail 阶段复用，封堵"DB online_status 滞后 ONLINE 但 Agent 已死"的误派窗口。</p>
     */
    public boolean isHeartbeatFresh(Agent agent) {
        if (agent == null || agent.getId() == null) {
            return false;
        }
        try {
            // API_KEY_LLM / WEB_BROWSER 列为"不需运行时心跳"，始终视为新鲜
            AgentAccessType accessType = agent.getAccessType();
            if (accessType != null && !accessType.requiresRuntimeLiveness()) {
                return true;
            }
            int thresholdMinutes = agentHealthProperties.getOfflineMinutes();
            if (thresholdMinutes <= 0) {
                // 关闭过滤（逃生口）
                return true;
            }
            OffsetDateTime lastSeen = agent.getLastSeenTime();
            if (lastSeen == null) {
                return false;
            }
            OffsetDateTime cutoff = OffsetDateTime.now().minus(Duration.ofMinutes(thresholdMinutes));
            return lastSeen.isAfter(cutoff);
        } catch (Exception e) {
            log.debug("isHeartbeatFresh fallback to false for agent {}: {}",
                    agent.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 凭证可用性检查：API_KEY_LLM 候选必须有可用执行凭证。
     *
     * <p>API_KEY_LLM 享有心跳/OFFLINE 双重豁免，若不校验凭证，历史遗留的无凭证 Agent
     * 永远是合格候选，选中即执。可用判定委托 {@link AgentLlmCredentialResolver}
     * （平台级模型配置密钥优先，Agent 级凭证兜底，与执行链解析顺序一致）。
     * 其它 accessType 不依赖凭证，直接放行。防御式：查询异常降级为排除
     * （选中无凭证 Agent 必败，排除更安全）。</p>
     */
    private boolean hasUsableCredential(Agent agent) {
        try {
            return agentLlmCredentialResolver.hasUsableCredential(agent);
        } catch (Exception e) {
            log.debug("凭证可用性判定异常，防御式降级为排除: agentId={}, reason={}",
                    agent != null ? agent.getId() : null, e.getMessage());
            return false;
        }
    }

    /**
     * 组装比较器：软优先级（字典序）→ 末位策略键。
     *
     * <p><b>末位为什么可替换</b>：{@code dutyRank → accessTypeRank → qualityRank → costRank}
     * 是「分类优先级」，必须保持字典序；而「同档内选谁」才是负载均衡的战场。
     * 切换末位键即可在不动分类语义的前提下改变分配均衡性。</p>
     *
     * <p>仅用于 {@code SCORE_DESC} 策略（末位 = score，原行为逐字不变）；
     * 加权随机策略改走 {@link #resolveTierComparator} + 层内抽签。</p>
     */
    private Comparator<Agent> resolveComparator(Map<Long, Integer> qualityRanks,
                                                Map<Long, Integer> costRanks) {
        // AgentHub P0-B：“当前是否处于值班”作为软优先级最高一档。
        // 无硬拒绝：即使无任何值班 Agent，仍能从非值班候选中选出，
        // 保证与当前行为向后兼容（未上线时 checkIn 未被调用，选择器表现与以前一致）。
        Comparator<Agent> dutyFirst = Comparator.comparingInt(this::dutyRank);
        if (agentDispatchProperties.isPreferExternal()) {
            dutyFirst = dutyFirst.thenComparingInt(this::accessTypeRank);
        }
        // 反馈回路第 1 层调度回灌：dutyRank 之后插入 qualityRank（质量分档位 ×
        // quality-weight，默认 0.1 低权重防抖动；0 关闭）。质量分缺失/查询异常
        // 档位记 0（不参与质量维度比较，不影响有画像 Agent 之间的相对顺序）。
        double qualityWeight = agentDispatchProperties.getQualityWeight();
        if (qualityWeight > 0) {
            dutyFirst = dutyFirst.thenComparingDouble(
                    a -> qualityRanks.getOrDefault(a.getId(), 0) * qualityWeight);
        }
        // B5.3 Fleet 成本回灌：在 qualityRank 之后、score 之前插入 costRank（成本分档位 ×
        // cost-weight，默认 0.1；0 关闭）。与 quality 完全同型——档位来源见
        // resolveCostRanks（候选内 min-max 反向归一 1~5，无成本数据记 0 档）。
        // ⚠️ 链式 thenComparing 是字典序而非加权求和：qualityRank 档位不同时
        // costRank 不生效；dutyRank 为最高档，故成本实际只在「同值班档」内起作用。
        double costWeight = agentDispatchProperties.getCostWeight();
        if (costWeight > 0) {
            dutyFirst = dutyFirst.thenComparingDouble(
                    a -> costRanks.getOrDefault(a.getId(), COST_RANK_NEUTRAL) * costWeight);
        }
        return dutyFirst.thenComparing(Agent::getScore,
                Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /**
     * 层级比较器（**不含 score**）：只表达「分类优先级」——值班 → 接入类型 → 质量档 → 成本档。
     *
     * <p>加权随机策略用它先划出「最优层级」，层内再按权重抽签：
     * 这样既不打乱既有分类语义（低质量档不会被抽上来），又能在同档内实现按权重分散。</p>
     */
    private Comparator<Agent> resolveTierComparator(Map<Long, Integer> qualityRanks,
                                                    Map<Long, Integer> costRanks) {
        Comparator<Agent> tier = Comparator.comparingInt(this::dutyRank);
        if (agentDispatchProperties.isPreferExternal()) {
            tier = tier.thenComparingInt(this::accessTypeRank);
        }
        double qualityWeight = agentDispatchProperties.getQualityWeight();
        if (qualityWeight > 0) {
            tier = tier.thenComparingDouble(
                    a -> qualityRanks.getOrDefault(a.getId(), 0) * qualityWeight);
        }
        double costWeight = agentDispatchProperties.getCostWeight();
        if (costWeight > 0) {
            tier = tier.thenComparingDouble(
                    a -> costRanks.getOrDefault(a.getId(), COST_RANK_NEUTRAL) * costWeight);
        }
        return tier;
    }

    /**
     * 预计算候选 Agent 的质量分档位（0~5 档：qualityScore/20），供比较器复用。
     *
     * <p>一次选人只查询一轮画像（避免 Comparator 两两比较时重复查库）；
     * weight ≤ 0 或查询异常一律记 0 档（best-effort，不阻断选人）。</p>
     */
    private Map<Long, Integer> resolveQualityRanks(List<Agent> candidates) {
        if (agentDispatchProperties.getQualityWeight() <= 0
                || candidates == null || candidates.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> ranks = new HashMap<>();
        for (Agent agent : candidates) {
            if (agent == null || agent.getId() == null) {
                continue;
            }
            try {
                Integer qualityScore = agentQualityProfileService.computeQualityScore(agent.getId());
                ranks.put(agent.getId(), qualityScore != null ? qualityScore / 20 : 0);
            } catch (Exception e) {
                // 防御式：画像查询异常降级为 0 档，不参与质量维度排序
                log.debug("qualityRank fallback to 0 for agent {}: {}", agent.getId(), e.getMessage());
                ranks.put(agent.getId(), 0);
            }
        }
        return ranks;
    }

    /**
     * 预计算候选 Agent 的<b>成本档位</b>（B5.3 Fleet 成本选人）。
     *
     * <p>原料：每个候选「最近 {@code costSampleLimit} 次成功执行」的 token 均值
     * （{@link AgentExecutionRecordService#averageRecentSuccessTokens}，越小越省）。</p>
     *
     * <p>归一方式：在<b>本批候选集合内</b>按均值做 min-max <b>反向</b>归一 → 档位
     * {@code 1~5}（最省 = {@link #COST_RANK_MAX}，最贵 = {@link #COST_RANK_MIN}）。
     * 采用相对档位而非绝对量纲，是因为 token 消耗没有天然上界（不同模型/任务量级差异极大），
     * 任何绝对阈值都会引入"拍脑袋常数"。</p>
     *
     * <p><b>缺失语义（2026-10-04 订正）</b>：无成本数据（无成功执行记录 / {@code token_usage}
     * 全 NULL / 查询异常）记 {@link #COST_RANK_NEUTRAL}（<b>中立档</b>）——「不可比」的正确落点是
     * <b>不偏袒</b>，而非记 {@code 0}。原实现记 {@code 0} 并在 {@code max(...)} 中比较，等价于
     * 「比最贵档还差」，会在<b>部分覆盖</b>（如候选内仅 1 个 Agent 有历史样本）时把
     * 「有历史样本」本身变成胜出理由，与「缺失值须与最差值区分」的判据相悖。</p>
     *
     * <p><b>可比性门槛</b>：候选内**有效样本数 &lt; 2** 时直接返回空 Map ⇒ 成本维度整体不生效
     * （无可比对象）；候选内成本无区分度（{@code min == max}）同样记
     * {@link #COST_RANK_NEUTRAL}（中立，不偏袒）。</p>
     *
     * <p>{@code costWeight ≤ 0} 时直接返回空 Map（完全关闭，与接入前行为一致）。
     * 一次选人只查询一轮（避免 Comparator 两两比较时重复查库，与
     * {@link #resolveQualityRanks} 同款范式）。</p>
     */
    private Map<Long, Integer> resolveCostRanks(List<Agent> candidates) {
        if (agentDispatchProperties.getCostWeight() <= 0
                || candidates == null || candidates.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Integer> avgTokens = new HashMap<>();
        int sampleLimit = agentDispatchProperties.getCostSampleLimit();
        for (Agent agent : candidates) {
            if (agent == null || agent.getId() == null) {
                continue;
            }
            try {
                Integer avg = agentExecutionRecordService.averageRecentSuccessTokens(agent.getId(), sampleLimit);
                if (avg != null) {
                    avgTokens.put(agent.getId(), avg);
                }
            } catch (Exception e) {
                // 防御式：成本画像查询异常降级为「无成本数据」（0 档），不阻断选人
                log.debug("costRank fallback to 0 for agent {}: {}", agent.getId(), e.getMessage());
            }
        }
        // 可比性门槛：候选内有效样本 < 2 时无比较对象 ⇒ 整维不生效（否则唯一有样本者凭空占优）
        if (avgTokens.size() < 2) {
            return Collections.emptyMap();
        }
        int min = avgTokens.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = avgTokens.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Map<Long, Integer> ranks = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : avgTokens.entrySet()) {
            ranks.put(entry.getKey(), inverseCostRank(entry.getValue(), min, max));
        }
        return ranks;
    }

    /**
     * min-max 反向归一到成本档位：最省 → {@link #COST_RANK_MAX}，最贵 → {@link #COST_RANK_MIN}。
     * 区间退化（{@code max <= min}）→ {@link #COST_RANK_NEUTRAL}。
     */
    private static int inverseCostRank(int avgTokens, int min, int max) {
        if (max <= min) {
            return COST_RANK_NEUTRAL;
        }
        int rank = (int) Math.round(COST_RANK_MAX * (double) (max - avgTokens) / (max - min));
        return Math.max(COST_RANK_MIN, Math.min(COST_RANK_MAX, rank));
    }

    /**
     * 值班优先的 rank：ACTIVE lease 存在 → 1，否则 0。
     *
     * <p>max() 取最大，因此值班 Agent 优先。安全兵：isOnDuty 内部容忍 agentId==null。</p>
     */
    private int dutyRank(Agent agent) {
        if (agent == null || agent.getId() == null) {
            return 0;
        }
        try {
            return agentDutyLeaseService.isOnDuty(agent.getId()) ? 1 : 0;
        } catch (Exception e) {
            // 防御式：任何 lease 查询异常都不影响选择（降级为非值班处理）
            log.debug("dutyRank fallback to 0 for agent {}: {}", agent.getId(), e.getMessage());
            return 0;
        }
    }

    private int accessTypeRank(Agent agent) {
        if (agent == null || agent.getAccessType() == null) return 0;
        return switch (agent.getAccessType()) {
            case CLI_CLIENT -> 3;
            case API_KEY_LLM -> 2;
            case WEB_BROWSER -> 1;
        };
    }

    /**
     * N12 P1 STRICT 独占报锁（ 第 1 段）语义：
     * 返回 true 表示该 Agent 当前以 STRICT 模式上岗，
     * 平台不应当把它列入他人失败/熔断后的替补池候选。
     *
     * <p>防御式：任何 lease 查询异常都降级为 false（不阻断选择）。</p>
     */
    private boolean isOnStrictDuty(Long agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            AgentDutyLease lease = agentDutyLeaseService.getActiveLease(agentId);
            if (lease == null) {
                return false;
            }
            return WorkMode.lenientParse(lease.getWorkMode()) == WorkMode.STRICT;
        } catch (Exception e) {
            log.debug("isOnStrictDuty fallback to false for agent {}: {}", agentId, e.getMessage());
            return false;
        }
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

    /**
     * 任务级选人约束（§6.58 P1）。
     *
     * <p>由任务 {@code agent_policy.executorAgentIds} 与 {@code required_skills}
     * 构建，注入选人链：白名单限定 + 技能 AND 匹配。约束为 null 或字段为空时
     * 一律不限制，与旧行为完全一致。</p>
     */
    @Data
    public static class AgentSelectionConstraints {

        /** 执行者白名单；null/空 = 不限定；非空 = 只允许集合内的 Agent。 */
        private List<Long> allowedAgentIds;

        /** 任务要求技能；null/空 = 不限定；非空 = Agent.skills 归一化后必须全部包含（AND 语义，A3 同义词互命中）。 */
        private List<String> requiredSkills;

        public static AgentSelectionConstraints of(List<Long> allowedAgentIds, List<String> requiredSkills) {
            AgentSelectionConstraints constraints = new AgentSelectionConstraints();
            constraints.setAllowedAgentIds(allowedAgentIds);
            constraints.setRequiredSkills(requiredSkills);
            return constraints;
        }

        /** 无限制约束（等价于传 null，供调用方语义化表达"不约束"）。 */
        public static AgentSelectionConstraints unrestricted() {
            return new AgentSelectionConstraints();
        }

        /** 候选是否满足约束：白名单内（若有）且技能全匹配（若有）。防御式：agent 为 null 直接拒绝。 */
        public boolean allows(Agent agent) {
            return denialReason(agent) == null;
        }

        /**
         * 判定候选被约束拒绝的<b>原因码</b>（未拒绝返回 {@code null}）。
         *
         * <p>与 {@link #allows(Agent)} <b>同源单实现</b>：{@code allows} 即
         * {@code denialReason(agent) == null}，故两者判定<b>逐字一致</b>、永不分叉。
         * 抽出原因码是为了让<b>非选人入口</b>（如 MCP 认领闸门）也能复用同一套白名单 /
         * 技能口径，并给出可读的拒绝理由（{@code not_in_executor_whitelist} /
         * {@code skill_not_matched}），避免规则在各入口各写一份。</p>
         *
         * <p><b>口径单源</b>：白名单取任务 {@code agent_policy.executorAgentIds}、
         * 技能取 {@code required_skills}（归一化后 AND 匹配，A3 同义词互命中），
         * 与自动派发链完全同款。</p>
         *
         * @param agent 待判定候选
         * @return {@code null}=满足约束；否则为拒绝原因码
         */
        public String denialReason(Agent agent) {
            if (agent == null) {
                return "agent_not_found";
            }
            if (allowedAgentIds != null && !allowedAgentIds.isEmpty()
                    && (agent.getId() == null || !allowedAgentIds.contains(agent.getId()))) {
                return "not_in_executor_whitelist";
            }
            if (requiredSkills != null && !requiredSkills.isEmpty()) {
                List<String> skills = agent.getSkills();
                // A3：匹配前归一化（trim + 小写 + 同义词归并），"powershell"/"bash" 与 "shell" 互相命中
                if (skills == null || skills.isEmpty() || !SkillNormalizer.matches(skills, requiredSkills)) {
                    return "skill_not_matched";
                }
            }
            return null;
        }
    }
}
