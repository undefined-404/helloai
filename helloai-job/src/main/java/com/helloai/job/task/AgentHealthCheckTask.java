package com.helloai.job.task;

import com.helloai.common.config.AgentHealthProperties;
import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.agent.service.AgentDutyLeaseService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 健康检查任务。
 *
 * <p>采用 Reconcile 模式：
 * <ol>
 *   <li>每 60s 扫描一次 last_seen_time 早于 5 分钟的 Agent</li>
 *   <li>对每个超时 Agent，先尝试主动 ping（占位）</li>
 *   <li>ping 失败 → 用 CAS UPDATE 标 OFFLINE（防止 seen() 刷新覆盖）</li>
 *   <li>CAS 成功 → 触发任务重分配（占位） + 写 task_timeline 审计</li>
 * </ol>
 * </p>
 *
 * <p>SLEEPING 防护：扫描阶段和 ping 阶段都跳过 SLEEPING Agent（管理员手动状态不被覆盖）。</p>
 *
 * <p><b>G-015 B1 止血（写侧与读侧同口径）</b>：v3 实测暴露「外部 Agent 埋头执行长任务
 * （数分钟不触网）→ 判 OFFLINE（heartbeat_lost）→ 1 秒内在飞子任务被重派打满 5 次
 * → DEAD_LETTER → 下游 DAG 级联卡死」。为此写侧加两道守卫：</p>
 * <ol>
 *   <li><b>值班租约守卫</b>：持 ACTIVE 租约（声明在岗）的 Agent 跳过离线处置——
 *       与 {@code HeartbeatServiceImpl.checkOnlineStatus} 读侧「租约 ACTIVE → IDLE」同口径，
 *       消除「租约 ACTIVE + dbOnlineStatus OFFLINE」双视图分裂；</li>
 *   <li><b>在飞子任务宽限</b>：持 ASSIGNED/IN_PROGRESS 子任务时改用
 *       {@link AgentHealthProperties#getInFlightGraceMinutes()}（默认 30 分钟）判断超时，
 *       阈值放宽而非取消，超过宽限仍会标 OFFLINE 并重派。</li>
 * </ol>
 *
 * <p><b>G-015 B2 阻断「快速死信」</b>（v3 实测：心跳抖动 → 3 轮重派打满预算 → DEAD_LETTER
 * → 下游 DAG 级联卡死）：</p>
 * <ol>
 *   <li><b>在飞任务保留归属</b>：IN_PROGRESS 子任务不再立刻重派，改置 PAUSED（见
 *       {@link #reassignStaleTasks}），Agent 回来可用 {@code startSubTask} 原地续做；</li>
 *   <li><b>PAUSED 超宽限回收</b>：超过宽限仍无心跳则经
 *       {@link SubTaskDispatchService#redispatchInProgress} 改派（见 {@link #reclaimExpiredPausedTasks}），
 *       避免永久停在 PAUSED；</li>
 *   <li><b>补偿路径不重复计数</b>：二次自动选人走
 *       {@link SubTaskDispatchService#dispatchPendingSubTaskCompensating}，单轮最多消耗 1 次预算；</li>
 *   <li><b>退避</b>：重派预算累加后按 60/180/600/1800s 逐级退避（见
 *       {@code SubTaskDispatchServiceImpl.REASSIGN_BACKOFF_SECONDS}）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentHealthCheckTask {

    private final TaskTimelineService taskTimelineService;
    private final AgentService agentService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final StringRedisTemplate redis;
    private final ExternalAgentFailureTracker failureTracker;
    private final AgentHealthProperties healthProperties;
    /** G-015 B1：写侧租约守卫——持 ACTIVE 租约视为声明在岗，不判离线。 */
    private final AgentDutyLeaseService agentDutyLeaseService;
    /** G-015 B2.3：离线在飞子任务置 PAUSED（保留归属）与 PAUSED 超宽限回收。 */
    private final SubTaskService subTaskService;

    /** OFFLINE 时 CAS 写入的 online_status 值（字符串，与 DB CHECK 约束对齐） */
    private static final String OFFLINE_STATUS = "OFFLINE";
    /** 离线原因标记（payload 中记录） */
    private static final String REASON_HEARTBEAT_LOST = "heartbeat_lost";

    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "agentHealthCheck", lockAtMostFor = "PT55S")
    public void checkHealth() {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            // §4.1：统一使用 AgentHealthProperties.offlineMinutes 计算 cutoff，
            // 与 AgentSelector / ExternalAgentFailureTracker 共用同一阈值，避免漂移。
            // 阈值 ≤ 0 时禁用扫描（逃生口，不推荐生产使用）。
            int thresholdMinutes = healthProperties.getOfflineMinutes();
            if (thresholdMinutes <= 0) {
                log.debug("AgentHealthCheckTask 已禁用扫描（offlineMinutes <= 0）");
                return;
            }
            // G-015 B1：在飞子任务宽限可能大于离线阈值，扫描窗口取较大者先扫出来，
            // 再由 processStaleAgent 按「是否持在飞子任务 / 租约」选择实际 cutoff。
            int graceMinutes = Math.max(healthProperties.getInFlightGraceMinutes(), thresholdMinutes);
            int scanMinutes = Math.max(thresholdMinutes, graceMinutes);
            OffsetDateTime cutoff = now.minusMinutes(scanMinutes);

            // 1) 扫描超时 Agent（已排除 SLEEPING 和已删除）
            List<Agent> staleAgents = agentService.listStaleSince(cutoff);
            if (staleAgents.isEmpty()) {
                return;
            }
            log.info("Agent 健康巡检: 发现 {} 个超时候选 Agent（offlineMinutes={}, scanMinutes={}）",
                    staleAgents.size(), thresholdMinutes, scanMinutes);

            for (Agent agent : staleAgents) {
                processStaleAgent(agent, thresholdMinutes, graceMinutes, now);
            }

        } catch (Exception e) {
            log.error("AgentHealthCheckTask 执行异常", e);
        }
    }

    /**
     * 处理单个超时 Agent。
     *
     * @param thresholdMinutes 常规离线阈值（无在飞子任务时使用）
     * @param graceMinutes     在飞子任务宽限阈值（持 ASSIGNED/IN_PROGRESS 时使用）
     */
    private void processStaleAgent(Agent agent, int thresholdMinutes, int graceMinutes, OffsetDateTime now) {
        // 双重防护：扫描阶段已过滤，这里再判断一次（防御性编程）
        if (agent.getOnlineStatus() == AgentOnlineStatus.SLEEPING) {
            log.debug("跳过 SLEEPING Agent: agentId={}", agent.getId());
            return;
        }

        // G-015 B1 守卫①：值班租约。读侧 checkOnlineStatus 对「心跳过期但持 ACTIVE 租约」
        // 返回 IDLE（声明在岗），写侧必须同口径——否则巡检仍会把在岗 Agent 标 OFFLINE，
        // 形成「租约 ACTIVE + dbOnlineStatus OFFLINE」双视图分裂，并误重派其在飞任务。
        if (hasActiveDutyLease(agent.getId())) {
            log.debug("持 ACTIVE 值班租约，跳过离线处置: agentId={}", agent.getId());
            return;
        }

        // 1) 主动 ping（占位：CLI 客户端通过 heartbeat 自动续约；API_KEY_LLM 暂用 last_active 判断）
        //    当前实现：仅靠 Redis TTL 二次验证（如果 Redis TTL 还在 → 说明刚刚见过，放弃标 OFFLINE）
        if (isRedisAlive(agent.getId())) {
            log.debug("Redis TTL 仍在，跳过: agentId={}", agent.getId());
            return;
        }

        // G-015 B2.3：先回收该 Agent 名下「PAUSED 已超宽限」的子任务（离线在飞任务的最终出路）。
        // 必须在 CAS 之前执行：Agent 一旦已被标 OFFLINE，后续轮次 markOfflineIfStale 会返回 0，
        // 若把回收挂在 CAS 成功分支内，PAUSED 任务将永远不被回收、DAG 永久卡住。
        reclaimExpiredPausedTasks(agent, Math.max(graceMinutes, 1));

        // G-015 B1 守卫②：在飞子任务宽限。执行者埋头跑长任务（写文档/推理数分钟不触网）
        // 时不应被 5 分钟心跳窗口判死；持 ASSIGNED/IN_PROGRESS 子任务时改用加长阈值。
        // 超宽限仍按常规流程标 OFFLINE 并重派，保留「真死」的可恢复性。
        boolean inFlight = hasInFlightSubTask(agent.getId());
        OffsetDateTime effectiveCutoff = now.minusMinutes(inFlight ? graceMinutes : thresholdMinutes);
        if (inFlight) {
            log.debug("持在飞子任务，应用宽限阈值: agentId={}, graceMinutes={}", agent.getId(), graceMinutes);
        }

        // 2) CAS 标 OFFLINE（防 seen() 刷新覆盖）
        int updated = agentService.markOfflineIfStale(
                agent.getId(),
                effectiveCutoff,
                OFFLINE_STATUS,
                REASON_HEARTBEAT_LOST,
                now);
        if (updated == 0) {
            // CAS 失败：可能 seen() 刚刷新，或 Agent 已变 SLEEPING，或在飞宽限未到期
            log.debug("CAS 标 OFFLINE 失败（心跳刚到或状态变化）: agentId={}", agent.getId());
            return;
        }

        log.warn("Agent 标 OFFLINE: agentId={}, name={}, role={}, lastSeen={}",
                agent.getId(), agent.getName(), agent.getRole(), agent.getLastSeenTime());

        // 3) 重新分配任务（返回发现的在跑任务数；无在跑任务不视为执行失败）
        int inFlightCount = reassignStaleTasks(agent);

        // 4) 写 task_timeline 审计
        AgentRole role = agent.getRole() != null ? agent.getRole() : AgentRole.EXECUTOR;
        taskTimelineService.recordEvent(
                null,  // 系统级事件，无主任务
                null,  // 系统级事件，无子任务
                "agent_offline",
                role,
                agent.getId(),
                Map.of(
                        "reason", REASON_HEARTBEAT_LOST,
                        "offline_time", now.toString(),
                        "last_seen_time", agent.getLastSeenTime() != null
                                ? agent.getLastSeenTime().toString() : "null",
                        "agent_name", agent.getName() != null ? agent.getName() : "unknown"
                ));

        // 5) N11 阈值回退计数（§6.58 语义修正）：仅当离线时有在跑任务（ASSIGNED/IN_PROGRESS）
        //    才视为执行失败——心跳丢失导致任务中断，语义成立；无在跑任务说明客户端只是
        //    "提交后停止心跳"的静默待命（trae 实测形态），若计入失败会把干活的 agent
        //    每完成一个任务就计 1 次失败，误伤 N11 阈值回退。
        //    已被 SQL 条件限定 access_type=CLI_CLIENT（API_KEY_LLM/WEB_BROWSER 不会写库）。
        if (inFlightCount > 0) {
            failureTracker.recordFailure(agent.getId());
        }
    }

    /**
     * Redis TTL 二次验证（替代主动 ping）。
     *
     * <p>HeartbeatService.seen() 会同时写 Redis TTL 和 DB last_seen_time，
     * 但 Redis 写入可能比 DB 慢（罕见），如果 DB 看起来超时但 Redis TTL 还在，
     * 说明心跳刚到，放弃标 OFFLINE。</p>
     */
    private boolean isRedisAlive(Long agentId) {
        try {
            String key = "agent:heartbeat:" + agentId;
            Boolean has = redis.hasKey(key);
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
            log.warn("Redis TTL 检查失败，按超时处理: agentId={}, err={}", agentId, e.getMessage());
            return false;
        }
    }

    /**
     * 是否持 ACTIVE 值班租约（G-015 B1 写侧守卫①）。
     *
     * <p>与 {@code HeartbeatServiceImpl.checkOnlineStatus} 读侧「租约 ACTIVE → IDLE」同口径。
     * 查询失败按无租约处理（不放宽离线结论，fail-close）。</p>
     */
    private boolean hasActiveDutyLease(Long agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            return agentDutyLeaseService.isOnDuty(agentId);
        } catch (Exception e) {
            log.debug("值班租约查询失败（按无租约处理）: agentId={}, err={}", agentId, e.getMessage());
            return false;
        }
    }

    /**
     * 是否持在飞子任务（G-015 B1 写侧守卫②）：{@code status ∈ {ASSIGNED, IN_PROGRESS}}。
     *
     * <p>口径与 {@link #reassignStaleTasks} 的重派范围一致——宽限只针对「会被重派的在飞任务」。
     * 查询失败按无在飞处理（不放宽阈值，fail-close）。</p>
     */
    private boolean hasInFlightSubTask(Long agentId) {
        if (agentId == null) {
            return false;
        }
        try {
            return subTaskService.existsInFlightAssignedOrInProgress(agentId);
        } catch (Exception e) {
            log.warn("在飞子任务查询失败（按无在飞处理，不放宽阈值）: agentId={}, err={}", agentId, e.getMessage());
            return false;
        }
    }

    /**
     * 处置离线 Agent 名下未完成的子任务（§4.1 二次选人加固 + G-015 B2.3 归属保留）。
     *
     * <p>处置范围：status ∈ {ASSIGNED, IN_PROGRESS} 且 assigned_agent = agentId。
     * PENDING 未分配具体 Agent，DONE/CANCELLED 已完成，均不处理。</p>
     *
     * <p><b>G-015 B2.3 分状态处置</b>：
     * IN_PROGRESS（正在执行）→ {@link SubTaskService#pause} 置 PAUSED 保留归属、不消耗重派预算，
     * 避免长耗时任务因心跳窗口被判死重派；ASSIGNED（尚未开工）→ 走下方重派链，
     * 因为此时还没有产生任何执行成果，改派无损失。</p>
     *
     * <p><b>§4.1 二次选人加固</b>：
     * 首选路径是 {@link SubTaskDispatchService#redispatchOfflineSubTask}，
     * 让原离线 Agent 触发 fast-fail + 熔断 fallback。
     * 当首选路径异常（如原 Agent 不在白名单、Selector 无候选）时，
     * 立即调用 {@link SubTaskDispatchService#dispatchPendingSubTaskCompensating}
     * 按原 Agent 的 role 重新选人（G-015 B2.1：补偿路径不再重复累加预算），
     * 角色取不到时回退 EXECUTOR。
     * 二次路径依赖首选路径已将任务重置为 PENDING；
     * 若首选发生在重置前导致状态被其他链路推进、二次路径会拒绝重新分配，
     * 此时仅记 failed，不覆盖新状态。</p>
     *
     * <p>计数口径：仅在任一路径真正成功时计为 reassigned；
     * 日志区分「置 PAUSED 保留归属」「弹性 fallback 成功」「自动重新选人成功」「两层均失败」。</p>
     *
     * @param staleAgent 离线 Agent 实体（传递实体以获取 role，避免二次查库）
     * @return 发现的在跑任务数（ASSIGNED/IN_PROGRESS），调用方据此决定是否计入失败
     */
    private int reassignStaleTasks(Agent staleAgent) {
        if (staleAgent == null || staleAgent.getId() == null) {
            return 0;
        }
        Long agentId = staleAgent.getId();
        AgentRole fallbackRole = staleAgent.getRole() != null
                ? staleAgent.getRole() : AgentRole.EXECUTOR;

        // 查询待重分配任务：ASSIGNED 或 IN_PROGRESS
        List<SubTask> staleTasks = subTaskService.listInFlightAssignedOrInProgress(agentId);

        if (staleTasks.isEmpty()) {
            log.info("Agent {} 离线，无待重分配任务", agentId);
            return 0;
        }

        log.warn("Agent {} 离线，待重分配任务数: {}", agentId, staleTasks.size());

        int pausedForRetention = 0;
        int reassignedByFallback = 0;
        int reassignedByAuto = 0;
        int failed = 0;

        for (SubTask task : staleTasks) {
            if (task.getStatus() == SubTaskStatus.IN_PROGRESS) {
                // G-015 B2.3：执行中的子任务不立刻重派——置 PAUSED 保留归属、**不消耗重派预算**。
                // 埋头执行长任务的 Agent（写文档/推理数分钟不触网）回来后可用 startSubTask
                // （状态白名单已含 PAUSED）原地续做，已完成的工作不作废；
                // 超过在飞宽限仍无心跳时，由 reclaimExpiredPausedTasks 走改派链回收。
                try {
                    subTaskService.pause(task.getId());
                    pausedForRetention++;
                    log.info("离线在飞子任务已置 PAUSED 保留归属: subTaskId={}, oldAgent={}",
                            task.getId(), agentId);
                } catch (Exception pauseException) {
                    log.warn("离线在飞子任务置 PAUSED 失败（状态可能已被其他链路推进）: subTaskId={}, oldAgent={}, err={}",
                            task.getId(), agentId, pauseException.getMessage());
                    failed++;
                }
                continue;
            }
            try {
                // 首选路径：原 Agent 触发弹性 fallback（resetToPending + assignNext）
                subTaskDispatchService.redispatchOfflineSubTask(task.getId(), agentId);
                log.info("任务重分配走弹性 fallback 成功: subTaskId={}, oldAgent={}",
                        task.getId(), agentId);
                reassignedByFallback++;
            } catch (Exception primaryException) {
                // 二次路径（G-015 B2.1）：按原 Agent 角色重新选人。走**不计数**的补偿重载——
                // 首选路径已消耗本轮预算，二次选人是同一次重派的补偿尝试，不应重复累加
                // （原先单轮最多 +2，3 轮即打满 5 次预算直入死信）。
                log.warn("首选 fallback 失败，尝试二次自动选人: subTaskId={}, oldAgent={}, err={}",
                        task.getId(), agentId, primaryException.getMessage());
                try {
                    subTaskDispatchService.dispatchPendingSubTaskCompensating(
                            task.getId(), fallbackRole);
                    log.info("任务重分配二次自动选人成功: subTaskId={}, oldAgent={}, role={}",
                            task.getId(), agentId, fallbackRole);
                    reassignedByAuto++;
                } catch (Exception secondaryException) {
                    log.error("任务重分配两层均失败: subTaskId={}, oldAgent={}, primary={}, secondary={}",
                            task.getId(), agentId,
                            primaryException.getMessage(),
                            secondaryException.getMessage());
                    failed++;
                }
            }
        }

        log.info("Agent {} 离线任务处置完成: total={}, pausedForRetention={}, fallback={}, autoReselect={}, failed={}",
                agentId, staleTasks.size(), pausedForRetention, reassignedByFallback, reassignedByAuto, failed);
        return staleTasks.size();
    }

    /**
     * 回收「该离线 Agent 名下、PAUSED 已超过在飞宽限」的子任务（G-015 B2.3 的最终出路）。
     *
     * <p>离线在飞的 IN_PROGRESS 子任务先被置 PAUSED 保留归属（见 {@link #reassignStaleTasks}），
     * 若 Agent 在宽限内恢复，用 {@code startSubTask} 原地续做；超过宽限仍无心跳，
     * 说明它确实不会回来了，此时经 {@link SubTaskDispatchService#redispatchInProgress}
     * （PAUSED → resume → block → 既有改派链）回收给替代 Agent，避免任务永久停在 PAUSED。</p>
     *
     * <p>把离线 Agent 作为 preferredAgentId 传入是既有约定：由 {@code TaskDispatchPort}
     * 的 fast-fail + fallback 选替代者，而不是让离线 Agent 真的接回任务。</p>
     *
     * @param staleAgent   长时静默（≥ 扫描窗口）的 Agent
     * @param graceMinutes 在飞宽限（PAUSED 停留超过此时长才回收）
     * @return 实际回收（发起改派）的任务数
     */
    private int reclaimExpiredPausedTasks(Agent staleAgent, int graceMinutes) {
        if (staleAgent == null || staleAgent.getId() == null) {
            return 0;
        }
        Long agentId = staleAgent.getId();
        OffsetDateTime expiredBefore = OffsetDateTime.now().minusMinutes(graceMinutes);
        List<SubTask> pausedExpired = subTaskService.listPausedBefore(agentId, expiredBefore);
        if (pausedExpired.isEmpty()) {
            return 0;
        }
        log.warn("Agent {} 名下 PAUSED 超过宽限（{} 分钟）的任务数: {}",
                agentId, graceMinutes, pausedExpired.size());
        int reclaimed = 0;
        for (SubTask task : pausedExpired) {
            try {
                subTaskDispatchService.redispatchInProgress(task.getId(), agentId);
                reclaimed++;
                log.info("PAUSED 超宽限已改派: subTaskId={}, oldAgent={}", task.getId(), agentId);
            } catch (Exception e) {
                log.warn("PAUSED 超宽限改派失败: subTaskId={}, oldAgent={}, err={}",
                        task.getId(), agentId, e.getMessage());
            }
        }
        log.info("Agent {} PAUSED 超宽限回收完成: scanned={}, reclaimed={}",
                agentId, pausedExpired.size(), reclaimed);
        return reclaimed;
    }

    /**
     * 当前 agent 状态枚举已不使用，避免编译器警告。
     */
    @SuppressWarnings("unused")
    private static final AgentStatus[] ALL_STATUSES = AgentStatus.values();
}
