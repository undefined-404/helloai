package com.helloai.job.task;

import com.helloai.common.config.AgentFallbackProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.agent.service.ExternalAgentFailureTracker;
import com.helloai.core.task.service.SubTaskDispatchService;
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

    private static final String LOCK_KEY = "scheduler:lock:ExternalAgentFallback";
    /** 单次扫描最多处理的子任务数（防止某轮候选 Agent 持有大量在跑任务时阻塞调度） */
    private static final int BATCH_LIMIT = 50;
    /** 单 Agent 最多拉多少在跑子任务 */
    private static final int PER_AGENT_LIMIT = 20;

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
            List<Agent> candidates = failureTracker.findFallbackCandidates();
            if (candidates.isEmpty()) {
                return;
            }
            log.info("N11 阈值回退扫描: 发现 {} 个超阈值 CLI_CLIENT Agent", candidates.size());

            int totalRedispatched = 0;
            for (Agent agent : candidates) {
                int redispatched = processCandidate(agent);
                totalRedispatched += redispatched;
            }
            log.info("N11 阈值回退扫描完成: candidateAgents={}, redispatchedSubTasks={}",
                    candidates.size(), totalRedispatched);

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
}
