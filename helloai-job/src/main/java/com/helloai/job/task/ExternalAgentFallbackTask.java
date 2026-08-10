package com.helloai.job.task;

import com.helloai.common.config.AgentFallbackProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.agent.service.ExternalAgentFailureTracker;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * N11 外部 Agent 阈值回退 — 周期补偿任务。
 *
 * <p>扫描 {@code agent.consecutive_failure_count >= threshold} 且不在 cooldown
 * 内的 CLI_CLIENT Agent，对其在跑子任务（ASSIGNED / IN_PROGRESS / REWORK）逐个
 * 调用 {@link SubTaskDispatchService#redispatchForFallback}，把它们重新分发给
 * 同角色的 API_KEY_LLM Agent；同时写 {@code sub_task_external_agent_fallback_triggered}
 * 审计事件 + 调用 {@link ExternalAgentFailureTracker#markFallbackTriggered} 标记冷却期。</p>
 *
 * <p>本任务与 {@link AgentHealthCheckTask} / {@link ExecutionCompensationTask} 的关系：
 * <ul>
 *   <li>健康检查：标 OFFLINE + 重新分配当前被占用的任务（reassignStaleTasks）</li>
 *   <li>执行补偿：超时回写失败 + 重新分配当前被占用的任务</li>
 *   <li>本任务：连续 N 次失败后，<b>不依赖 OFFLINE 判定</b>，纯按计数阈值跨周期触发，
 *       把"偶发但持续失败"的外部 Agent 的所有在跑任务一次性切到平台内 LLM</li>
 * </ul>
 * </p>
 *
 * @see ExternalAgentFailureTracker
 * @see SubTaskDispatchService#redispatchForFallback
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalAgentFallbackTask {

    private final ExternalAgentFailureTracker failureTracker;
    private final SubTaskDispatchService subTaskDispatchService;
    private final SubTaskMapper subTaskMapper;
    private final TaskTimelineService taskTimelineService;
    private final AgentFallbackProperties properties;
    private final StringRedisTemplate redis;
    private final SubTaskService subTaskService;

    private static final String LOCK_KEY = "scheduler:lock:ExternalAgentFallback";
    /** 单次扫描最多处理的子任务数（防止某轮候选 Agent 持有大量在跑任务时阻塞调度） */
    private static final int BATCH_LIMIT = 50;
    /** 单 Agent 最多拉多少在跑子任务 */
    private static final int PER_AGENT_LIMIT = 20;
    /** v2.6 §4.1：调度链遗留 PENDING 未指派子任务单轮处理上限 */
    private static final int PENDING_ORPHAN_BATCH_LIMIT = 50;

    @Scheduled(fixedDelayString = "${helloai.dispatch.fallback.scan-interval-ms:60000}",
               initialDelay = 30_000L)
    public void scan() {
        if (!properties.isEnabled()) {
            log.debug("ExternalAgentFallbackTask 跳过 (helloai.dispatch.fallback.enabled=false)");
            return;
        }
        if (!tryLock()) {
            log.debug("ExternalAgentFallbackTask 跳过（其他实例正在执行）");
            return;
        }

        try {
            // 阶段 A：N11 阈值回退 — 处理超阈值候选 Agent 的在跑子任务
            List<Agent> candidates = failureTracker.findFallbackCandidates();
            if (!candidates.isEmpty()) {
                log.info("N11 阈值回退扫描: 发现 {} 个超阈值 CLI_CLIENT Agent", candidates.size());
                int totalRedispatched = 0;
                for (Agent agent : candidates) {
                    int redispatched = processCandidate(agent);
                    totalRedispatched += redispatched;
                }
                log.info("N11 阈值回退扫描完成: candidateAgents={}, redispatchedSubTasks={}",
                        candidates.size(), totalRedispatched);
            } else {
                log.debug("N11 阈值回退扫描: 本轮无超阈值候选 Agent");
            }

            // 阶段 B：v2.6 §4.1 调度链遗留 PENDING 未指派兜底（全局唯一一次）
            // 与阶段 A 共享同一 Redis 锁，但作为独立阶段；
            // 即使没有 N11 候选也要执行，避免仅依赖阶段 A。
            recoverPendingUnassigned();

        } catch (Exception e) {
            log.error("ExternalAgentFallbackTask 执行异常", e);
        } finally {
            unlock();
        }
    }

    /**
     * 对单个超阈值 Agent 触发回退。
     *
     * <p>先写 {@code sub_task_external_agent_fallback_triggered} 审计 + 调用
     * {@link ExternalAgentFailureTracker#markFallbackTriggered} 标记冷却，
     * 避免后续 cycle 又把它当作候选；再逐个重新分发在跑子任务。</p>
     */
    private int processCandidate(Agent agent) {
        List<SubTask> inFlight = subTaskMapper.selectInFlightByAgent(agent.getId(), PER_AGENT_LIMIT);
        if (inFlight.isEmpty()) {
            // 没有在跑任务，仍然清零计数 + 写 last_fallback_at，避免下一轮重复扫描
            failureTracker.markFallbackTriggered(agent.getId());
            log.info("N11 阈值回退: agentId={} 无在跑子任务，仅写冷却标记", agent.getId());
            return 0;
        }

        taskTimelineService.recordEvent(
                null,  // 系统级事件
                null,  // 系统级事件
                "agent_external_fallback_triggered",
                agent.getRole() != null ? agent.getRole() : AgentRole.EXECUTOR,
                agent.getId(),
                Map.of(
                        "reason", "consecutive_failure_threshold_exceeded",
                        "consecutiveFailureCount", agent.getConsecutiveFailureCount(),
                        "inFlightSubTaskCount", inFlight.size(),
                        "at", OffsetDateTime.now().toString(),
                        "agentName", agent.getName() != null ? agent.getName() : "unknown"));

        int success = 0;
        int failed = 0;
        int processed = 0;
        for (SubTask subTask : inFlight) {
            if (processed >= BATCH_LIMIT) {
                log.warn("N11 阈值回退: agentId={} 已达本轮处理上限 {}, 剩余任务下一轮处理",
                        agent.getId(), BATCH_LIMIT);
                break;
            }
            try {
                String reason = String.format(
                        "consecutive_failure=%d >= threshold=%d",
                        agent.getConsecutiveFailureCount(), properties.getFailureThreshold());
                subTaskDispatchService.redispatchForFallback(subTask.getId(), agent.getId(), reason);
                // 累加 sub_task.external_fallback_count（重置状态后再次写）
                subTaskMapper.incrementExternalFallbackCount(
                        subTask.getId(), OffsetDateTime.now());
                log.info("N11 阈值回退重分发: subTaskId={}, failedAgentId={}, newAgentId=API_KEY_LLM",
                        subTask.getId(), agent.getId());
                success++;
            } catch (Exception e) {
                failed++;
                log.error("N11 阈值回退重分发失败: subTaskId={}, failedAgentId={}",
                        subTask.getId(), agent.getId(), e);
            }
            processed++;
        }

        // 触发后再写一次冷却：避免下一轮又把该 Agent 当作候选
        failureTracker.markFallbackTriggered(agent.getId());

        log.info("N11 阈值回退 agentId={} 汇总: inFlight={}, success={}, failed={}, processed={}",
                agent.getId(), inFlight.size(), success, failed, processed);
        return success;
    }

    private boolean tryLock() {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", 60, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock() {
        redis.delete(LOCK_KEY);
    }

    // ══════════════════════════════════════════════════════════════
    //  v2.6 §4.1：调度链遗留 PENDING 未指派子任务全局兜底
    // ══════════════════════════════════════════════════════════════

    /**
     * 兜底处理"有历史 execution record 但无活跃 record、且处于 PENDING 未指派"的调度链遗留任务。
     *
     * <p>目标：覆盖"离线重派在 reset 后失败留下"的子任务。判定 SQL 见
     * {@code SubTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord}。</p>
     *
     * <p>职责划分：
     * <ul>
     *   <li>无 execution record 的陈旧 PENDING → 由 SubTaskPendingOrphanTask 处理</li>
     *   <li>本方法覆盖"有历史 record、无活跃 record、PENDING 且未指派"</li>
     *   <li>有活跃 PENDING/RUNNING record → 继续交给 Poller/补偿链</li>
     * </ul>
     *
     * <p>每条记录：先按 id 补读最新状态，并发状态变化按跳过处理，
     * 仍为 PENDING 且未指派时按 EXECUTOR 角色调用
     * {@link SubTaskDispatchService#dispatchPendingSubTaskAuto} 重新选人。</p>
     *
     * <p>本兜底只执行一次（在 N11 阶段 A 后独立执行），不重复计数、不累加
     * {@code external_fallback_count}、不计入某个 Agent 的 N11 冷却。
     * 不放进每个候选 Agent 循环，避免重复重派。</p>
     */
    private void recoverPendingUnassigned() {
        List<Long> orphanIds;
        try {
            orphanIds = subTaskMapper.selectPendingUnassignedWithoutActiveExecutionRecord(
                    PENDING_ORPHAN_BATCH_LIMIT);
        } catch (Exception e) {
            log.error("扫描调度链遗留 PENDING 未指派任务失败", e);
            return;
        }
        if (orphanIds.isEmpty()) {
            log.debug("调度链遗留 PENDING 未指派兜底: 本轮无目标");
            return;
        }
        log.info("调度链遗留 PENDING 未指派兜底: 发现 {} 个候选", orphanIds.size());

        int recovered = 0;
        int skipped = 0;
        int failed = 0;

        for (Long subTaskId : orphanIds) {
            try {
                SubTask latest = subTaskService.getById(subTaskId);
                if (latest == null) {
                    skipped++;
                    continue;
                }
                // 并发状态变化：不再是 PENDING 或已被指派则跳过，不强制覆盖
                if (latest.getStatus() != SubTaskStatus.PENDING
                        || latest.getAssignedAgentId() != null) {
                    log.debug("调度链遗留任务状态已变化，跳过: subTaskId={}, status={}, assignedAgentId={}",
                            subTaskId, latest.getStatus(), latest.getAssignedAgentId());
                    skipped++;
                    continue;
                }
                // V27.1: 有人工介入标记的 PENDING 不自动重派（等人工处置），
                // 避免兜底链路把"无能力/返工超限"等人工场景反复打回调度链
                if (SubTaskDispatchService.isManualInterventionMarked(latest)) {
                    log.debug("调度链遗留任务已标记人工介入，跳过: subTaskId={}", subTaskId);
                    skipped++;
                    continue;
                }
                // 仍为 PENDING 且未指派，按 EXECUTOR 角色重新选人
                subTaskDispatchService.dispatchPendingSubTaskAuto(
                        subTaskId, AgentRole.EXECUTOR);
                log.info("调度链遗留 PENDING 未指派兜底成功: subTaskId={}", subTaskId);
                recovered++;
            } catch (Exception e) {
                log.error("调度链遗留 PENDING 未指派兜底失败: subTaskId={}", subTaskId, e);
                failed++;
            }
        }

        log.info("调度链遗留 PENDING 未指派兜底完成: candidates={}, recovered={}, skipped={}, failed={}",
                orphanIds.size(), recovered, skipped, failed);
    }
}
