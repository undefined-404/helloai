package com.helloai.job.task;

import com.helloai.common.constant.AgentOnlineStatus;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import com.helloai.core.mapper.AgentMapper;
import com.helloai.core.mapper.SubTaskMapper;
import com.helloai.core.service.AgentService;
import com.helloai.core.service.ExternalAgentFailureTracker;
import com.helloai.core.service.SubTaskDispatchService;
import com.helloai.core.service.TaskTimelineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * Agent 健康检查任务（v2.4 阶段 4.2 重构）。
 *
 * <p>采用 Reconcile 模式（v2.4 P2）：
 * <ol>
 *   <li>每 60s 扫描一次 last_seen_at 早于 5 分钟的 Agent</li>
 *   <li>对每个超时 Agent，先尝试主动 ping（v1.1 占位）</li>
 *   <li>ping 失败 → 用 CAS UPDATE 标 OFFLINE（防止 seen() 刷新覆盖）</li>
 *   <li>CAS 成功 → 触发任务重分配（v1.1 占位） + 写 task_timeline 审计</li>
 * </ol>
 * </p>
 *
 * <p>SLEEPING 防护：扫描阶段和 ping 阶段都跳过 SLEEPING Agent（管理员手动状态不被覆盖）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentHealthCheckTask {

    private final AgentMapper agentMapper;
    private final SubTaskMapper subTaskMapper;
    private final TaskTimelineService taskTimelineService;
    private final AgentService agentService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final StringRedisTemplate redis;
    private final ExternalAgentFailureTracker failureTracker;

    private static final String LOCK_KEY = "scheduler:lock:AgentHealth";

    /** 5 分钟未心跳 → 视为离线候选 */
    private static final int STALE_THRESHOLD_MINUTES = 5;
    /** OFFLINE 时 CAS 写入的 online_status 值（字符串，与 DB CHECK 约束对齐） */
    private static final String OFFLINE_STATUS = "OFFLINE";
    /** 离线原因标记（payload 中记录） */
    private static final String REASON_HEARTBEAT_LOST = "heartbeat_lost";

    @Scheduled(fixedRate = 60000)
    public void checkHealth() {
        if (!tryLock()) {
            log.debug("AgentHealthCheckTask 跳过（其他实例正在执行）");
            return;
        }

        try {
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime cutoff = now.minusMinutes(STALE_THRESHOLD_MINUTES);

            // 1) 扫描超时 Agent（已排除 SLEEPING 和已删除）
            List<Agent> staleAgents = agentMapper.selectByLastSeenBefore(cutoff);
            if (staleAgents.isEmpty()) {
                return;
            }
            log.info("Agent 健康巡检: 发现 {} 个超时候选 Agent", staleAgents.size());

            for (Agent agent : staleAgents) {
                processStaleAgent(agent, cutoff, now);
            }

        } catch (Exception e) {
            log.error("AgentHealthCheckTask 执行异常", e);
        } finally {
            unlock();
        }
    }

    /**
     * 处理单个超时 Agent。
     */
    private void processStaleAgent(Agent agent, OffsetDateTime cutoff, OffsetDateTime now) {
        // 双重防护：扫描阶段已过滤，这里再判断一次（防御性编程）
        if (agent.getOnlineStatus() == AgentOnlineStatus.SLEEPING) {
            log.debug("跳过 SLEEPING Agent: agentId={}", agent.getId());
            return;
        }

        // 1) 主动 ping（v1.1 占位：CLI 客户端通过 heartbeat 自动续约；API_KEY_LLM 暂用 last_active 判断）
        //    当前实现：仅靠 Redis TTL 二次验证（如果 Redis TTL 还在 → 说明刚刚见过，放弃标 OFFLINE）
        if (isRedisAlive(agent.getId())) {
            log.debug("Redis TTL 仍在，跳过: agentId={}", agent.getId());
            return;
        }

        // 2) CAS 标 OFFLINE（防 seen() 刷新覆盖）
        int updated = agentMapper.markOfflineIfStale(
                agent.getId(),
                cutoff,
                OFFLINE_STATUS,
                REASON_HEARTBEAT_LOST,
                now);
        if (updated == 0) {
            // CAS 失败：可能 seen() 刚刷新，或 Agent 已变 SLEEPING
            log.debug("CAS 标 OFFLINE 失败（心跳刚到或状态变化）: agentId={}", agent.getId());
            return;
        }

        log.warn("Agent 标 OFFLINE: agentId={}, name={}, role={}, lastSeen={}",
                agent.getId(), agent.getName(), agent.getRole(), agent.getLastSeenAt());

        // 3) 重新分配任务（v1.1 占位：阶段 4.6 AgentSelector.pickAlternative() 完成后再实装）
        reassignStaleTasks(agent.getId());

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
                        "offline_at", now.toString(),
                        "last_seen_at", agent.getLastSeenAt() != null
                                ? agent.getLastSeenAt().toString() : "null",
                        "agent_name", agent.getName() != null ? agent.getName() : "unknown"
                ));

        // 5) N11 阈值回退计数：心跳丢失 / 离线被视为执行失败。
        // 已被 SQL 条件限定 access_type=CLI_CLIENT（API_KEY_LLM/WEB_BROWSER 不会写库）。
        failureTracker.recordFailure(agent.getId());
    }

    /**
     * Redis TTL 二次验证（v1.1 阶段替代 ping）。
     *
     * <p>HeartbeatService.seen() 会同时写 Redis TTL 和 DB last_seen_at，
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
     * 将离线 Agent 的未完成任务重分配给同角色替代 Agent（v2.4 §4.6）。
     *
     * <p>重分配范围：status ∈ {ASSIGNED, IN_PROGRESS} 且 assigned_agent = agentId。
     * PENDING 未分配具体 Agent，DONE/CANCELLED 已完成，均不处理。</p>
     *
     * <p>重分配会先将子任务重置为 PENDING，再交给
     * {@link com.helloai.core.service.ResilientDispatcher} 处理，
     * 由 fast-fail + fallback 自动选择替代 Agent，并重新触发 ASSIGNED 事件。</p>
     */
    private void reassignStaleTasks(Long agentId) {
        // 查询待重分配任务：ASSIGNED 或 IN_PROGRESS
        List<SubTask> staleTasks = subTaskMapper.selectList(
                new LambdaQueryWrapper<SubTask>()
                        .eq(SubTask::getAssignedAgent, agentId)
                        .in(SubTask::getStatus, SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS));

        if (staleTasks.isEmpty()) {
            log.info("Agent {} 离线，无待重分配任务", agentId);
            return;
        }

        log.warn("Agent {} 离线，待重分配任务数: {}", agentId, staleTasks.size());

        int reassigned = 0;
        int failed = 0;

        for (SubTask task : staleTasks) {
            try {
                subTaskDispatchService.redispatchOfflineSubTask(task.getId(), agentId);
                log.info("任务重分配已重新进入弹性调度: subTaskId={}, oldAgent={}",
                        task.getId(), agentId);
                reassigned++;
            } catch (Exception e) {
                log.error("任务重分配失败: subTaskId={}, agentId={}", task.getId(), agentId, e);
                failed++;
            }
        }

        log.info("Agent {} 离线任务重分配完成: total={}, reassigned={}, failed={}",
                agentId, staleTasks.size(), reassigned, failed);
    }

    private boolean tryLock() {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", 55, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock() {
        redis.delete(LOCK_KEY);
    }

    /**
     * 当前 agent 状态枚举已不使用，避免编译器警告。
     */
    @SuppressWarnings("unused")
    private static final AgentStatus[] ALL_STATUSES = AgentStatus.values();
}
