package com.helloai.core.task.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.dispatcher.ResilientDispatcher;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.executor.AgentSelector.AgentSelectionConstraints;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子任务调度分配服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskDispatchServiceImpl implements SubTaskDispatchService {

    private final SubTaskService subTaskService;
    private final ResilientDispatcher resilientDispatcher;
    private final TaskTimelineService taskTimelineService;
    private final AgentSelector agentSelector;
    private final AgentService agentService;
    private final SubTaskMapper subTaskMapper;
    private final AgentDispatchProperties agentDispatchProperties;
    private final TaskService taskService;

    @Override
    public void dispatchBlockedSubTask(Long subTaskId, Long preferredAgentId) {
        // V24: 重分配熔断检查
        if (checkReassignCircuitBreaker(subTaskId)) {
            return;
        }
        SubTask subTask = subTaskService.resetToPendingForDispatch(
                subTaskId, Set.of(SubTaskStatus.BLOCKED));
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_dispatch_prepare",
                AgentRole.PLANNER,
                preferredAgentId,
                Map.of(
                        "trigger", "blocked_reassign",
                        "preferredAgentId", preferredAgentId));
        resilientDispatcher.assignNext(preferredAgentId, subTaskId, resolveConstraints(subTask));
        log.info("阻塞子任务重新进入调度: subTaskId={}, preferredAgentId={}", subTaskId, preferredAgentId);
    }

    @Override
    public void redispatchOfflineSubTask(Long subTaskId, Long offlineAgentId) {
        // V24: 重分配熔断检查
        if (checkReassignCircuitBreaker(subTaskId)) {
            return;
        }
        SubTask subTask = subTaskService.resetToPendingForDispatch(
                subTaskId, Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS));
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        // V27.1: 依赖 ready 守卫 —— 未就绪的离线遗留任务不重派，保持 PENDING
        // 等依赖 DONE 后由 SubTaskPendingOrphanTask / 自动分发链再次触发
        if (!subTaskService.isReady(subTask)) {
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    subTask.getId(),
                    "sub_task_dispatch_skip_dependency",
                    AgentRole.SYSTEM,
                    offlineAgentId,
                    Map.of("trigger", "agent_offline",
                            "reason", "dependency_not_ready",
                            "dependsOn", subTask.dependsOnIdList()));
            log.info("离线子任务依赖未就绪，保持 PENDING 等待依赖完成: subTaskId={}, dependsOn={}",
                    subTaskId, subTask.dependsOnIdList());
            return;
        }
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_dispatch_prepare",
                AgentRole.SYSTEM,
                offlineAgentId,
                Map.of(
                        "trigger", "agent_offline",
                        "preferredAgentId", offlineAgentId,
                        "previousAgentId", offlineAgentId));
        resilientDispatcher.assignNext(offlineAgentId, subTaskId, resolveConstraints(subTask));
        log.info("离线子任务重新进入调度: subTaskId={}, offlineAgentId={}", subTaskId, offlineAgentId);
    }

    @Override
    public Long dispatchPendingSubTaskAuto(Long subTaskId, AgentRole role) {
        // V27: 依赖 ready 守卫必须在熔断计数之前 —— 未就绪的 PENDING 子任务
        // 会被定时兜底任务反复扫描，若先累加 reassign_attempt_count 会被误推入死信
        SubTask readyCheck = subTaskService.getById(subTaskId);
        if (readyCheck != null && readyCheck.getStatus() == SubTaskStatus.PENDING
                && !subTaskService.isReady(readyCheck)) {
            log.debug("子任务依赖未就绪，跳过分发（保持 PENDING）: subTaskId={}, dependsOn={}",
                    subTaskId, readyCheck.dependsOnIdList());
            return null;
        }
        // V25: 重分配熔断检查 —— 封堵定时兜底任务（PendingOrphan / recoverPendingUnassigned /
        // HealthCheck 二次选人）经本入口无限改派的旁路
        if (checkReassignCircuitBreaker(subTaskId)) {
            return null;
        }
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() != SubTaskStatus.PENDING) {
            throw new BizException("只有 PENDING 状态的子任务才能自动分配: subTaskId=" + subTaskId + ", status=" + subTask.getStatus());
        }

        // V47：任务级选人约束（executorAgentIds 白名单 + required_skills 技能 AND 匹配）
        AgentSelectionConstraints constraints = resolveConstraints(subTask);
        var preferred = agentSelector.pickPreferred(role, constraints);
        if (preferred == null) {
            throw new BizException("无可用候选 Agent: role=" + role);
        }

        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_dispatch_prepare",
                AgentRole.SYSTEM,
                preferred.getId(),
                Map.of(
                        "trigger", "auto_assign",
                        "preferredAgentId", preferred.getId(),
                        "role", role != null ? role.name() : "null"));

        resilientDispatcher.assignNext(preferred.getId(), subTaskId, constraints);
        log.info("子任务自动分配进入调度链: subTaskId={}, preferredAgentId={}, role={}",
                subTaskId, preferred.getId(), role);
        return preferred.getId();
    }

    @Override
    public void redispatchDeadLetter(Long subTaskId, Long agentId) {
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() != SubTaskStatus.DEAD_LETTER) {
            throw new BizException("只有 DEAD_LETTER 状态的子任务才能人工兜底指派: subTaskId="
                    + subTaskId + ", status=" + subTask.getStatus());
        }
        if (agentId == null) {
            throw new BizException("人工兜底指派必须指定目标 Agent: subTaskId=" + subTaskId);
        }
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }

        // 清零熔断计数，重新投入调度链后从头计数
        subTaskMapper.resetReassignAttemptCount(subTaskId, OffsetDateTime.now());

        // 直接指派（DEAD_LETTER → ASSIGNED，状态机已允许）
        subTaskService.changeStatus(subTaskId, SubTaskStatus.ASSIGNED, agentId);

        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_dead_letter_manual_assign",
                AgentRole.SYSTEM,
                agentId,
                Map.of(
                        "trigger", "manual_dead_letter_redispatch",
                        "assignedAgentId", agentId,
                        "agentName", agent.getName() != null ? agent.getName() : "unknown"));
        log.info("死信子任务人工兜底指派完成: subTaskId={}, agentId={}", subTaskId, agentId);
    }

    @Override
    public Long redispatchForFallback(Long subTaskId, Long failedAgentId, String reason) {
        // V24: 重分配熔断检查
        if (checkReassignCircuitBreaker(subTaskId)) {
            return null;
        }
        SubTask subTask = subTaskService.resetToPendingForDispatch(
                subTaskId, Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS,
                        SubTaskStatus.BLOCKED, SubTaskStatus.REWORK));
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        // 角色从失败 Agent 推导：SubTask 本身不存角色，失败 Agent 的 role 决定了
        // 我们要选哪个 role 的 API_KEY_LLM Agent 接替；取不到时回退 EXECUTOR。
        Agent failedAgent = failedAgentId != null ? agentService.getById(failedAgentId) : null;
        final AgentRole role = (failedAgent != null && failedAgent.getRole() != null)
                ? failedAgent.getRole() : AgentRole.EXECUTOR;

        // V47（§6.58 P1）：任务级回退策略约束——fallbackPolicy=NONE 或 difficulty=HIGH
        // 时禁止 N11 自动回退，改打人工介入标记等人工处置，避免高风险任务被静默换人。
        Map<String, Object> agentPolicy = loadAgentPolicy(subTask);
        if (TaskAgentPolicy.isFallbackForbidden(agentPolicy)) {
            if (SubTaskDispatchService.isManualInterventionMarked(subTask)) {
                log.debug("人工介入标记已存在，跳过重复回退: subTaskId={}", subTaskId);
                return null;
            }
            taskTimelineService.recordEvent(subTask.getTaskId(), subTask.getId(),
                    "sub_task_fallback_skip_policy", AgentRole.SYSTEM, failedAgentId,
                    Map.of("reason", "fallback_policy_forbidden",
                            "fallbackPolicy", TaskAgentPolicy.fallbackPolicy(agentPolicy).name(),
                            "difficulty", TaskAgentPolicy.difficulty(agentPolicy).name(),
                            "previousAgentId", failedAgentId));
            subTaskService.markManualIntervention(subTaskId, "fallback_skip_policy",
                    Map.of("failedAgentId", failedAgentId == null ? "" : failedAgentId,
                            "fallbackPolicy", TaskAgentPolicy.fallbackPolicy(agentPolicy).name(),
                            "difficulty", TaskAgentPolicy.difficulty(agentPolicy).name()));
            log.warn("N11 回退跳过：任务级策略禁止自动回退, subTaskId={}, fallbackPolicy={}, difficulty={}",
                    subTaskId, TaskAgentPolicy.fallbackPolicy(agentPolicy),
                    TaskAgentPolicy.difficulty(agentPolicy));
            return null;
        }
        Agent fallbackAgent = pickApiKeyLlmAgent(role);

        if (fallbackAgent == null) {
            String msg = String.format(
                    "N11 阈值回退失败：未找到同角色(role=%s) 的 API_KEY_LLM Agent，subTaskId=%d",
                    role, subTaskId);
            log.error(msg);
            throw new BizException(msg);
        }

        // V47：RESTRICTED 回退——仅允许回退到 executorAgentIds 内的 API_KEY_LLM Agent；
        // 集合为空或回退目标不在集合内时等同 NONE，打人工介入标记。
        if (TaskAgentPolicy.fallbackPolicy(agentPolicy) == TaskAgentPolicy.FallbackPolicy.RESTRICTED) {
            List<Long> allowed = TaskAgentPolicy.executorAgentIds(agentPolicy);
            if (allowed.isEmpty() || !allowed.contains(fallbackAgent.getId())) {
                taskTimelineService.recordEvent(subTask.getTaskId(), subTask.getId(),
                        "sub_task_fallback_skip_policy", AgentRole.SYSTEM, fallbackAgent.getId(),
                        Map.of("reason", "fallback_policy_restricted_not_in_whitelist",
                                "fallbackAgentId", fallbackAgent.getId(),
                                "previousAgentId", failedAgentId));
                subTaskService.markManualIntervention(subTaskId, "fallback_skip_policy_restricted",
                        Map.of("failedAgentId", failedAgentId == null ? "" : failedAgentId,
                                "fallbackAgentId", fallbackAgent.getId()));
                log.warn("N11 回退跳过：RESTRICTED 策略下回退目标不在执行者白名单, subTaskId={}, fallbackAgentId={}",
                        subTaskId, fallbackAgent.getId());
                return null;
            }
        }

        // §6.52 能力预检：执行密集任务（需本机 shell/文件/服务操作）不自动回退给无本机能力的
        // API_KEY_LLM，避免"无能力执行 → 交付物不达标 → 返工循环 → 卡死审核"。改停留原状态
        // 并标记人工介入：外部 agent 回线后可由既有重调度/claim 路径接回，或用户在前端人工改派。
        if (agentDispatchProperties.isFallbackSkipExecutionDense() && SubTaskDispatchService.isExecutionDense(subTask)) {
            if (SubTaskDispatchService.isManualInterventionMarked(subTask)) {
                log.debug("人工介入标记已存在，跳过重复回退: subTaskId={}", subTaskId);
                return null;
            }
            if (!SubTaskDispatchService.hasLocalExecutionCapability(fallbackAgent)) {
                taskTimelineService.recordEvent(subTask.getTaskId(), subTask.getId(),
                        "sub_task_fallback_skip_need_human", AgentRole.SYSTEM, fallbackAgent.getId(),
                        Map.of("reason", "execution_dense_no_local_capability",
                                "fallbackAgentId", fallbackAgent.getId(),
                                "previousAgentId", failedAgentId));
                subTaskService.markManualIntervention(subTaskId, "fallback_skip_execution_dense",
                        Map.of("failedAgentId", failedAgentId == null ? "" : failedAgentId,
                                "fallbackAgentId", fallbackAgent.getId()));
                log.warn("N11 回退跳过：执行密集任务不可回退给无本机能力 Agent, subTaskId={}, fallbackAgentId={}",
                        subTaskId, fallbackAgent.getId());
                return null;
            }
        }

        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_dispatch_prepare",
                AgentRole.SYSTEM,
                fallbackAgent.getId(),
                Map.of(
                        "trigger", "external_fallback",
                        "preferredAgentId", fallbackAgent.getId(),
                        "previousAgentId", failedAgentId,
                        "reason", reason != null ? reason : ""));

        resilientDispatcher.assignNext(fallbackAgent.getId(), subTaskId);
        log.info("N11 阈值回退已重新进入调度链: subTaskId={}, failedAgentId={}, fallbackAgentId={}",
                subTaskId, failedAgentId, fallbackAgent.getId());
        return fallbackAgent.getId();
    }

    /**
     * 在同角色 EXECUTOR/PLANNER/REVIEWER 中按 score 降序选一个
     * access_type=API_KEY_LLM 且 status=ACTIVE 的 Agent。
     *
     * <p>简单实现：基于 {@code AgentService.listActive} + stream filter。
     * 不复用 {@link AgentSelector} 是为了彻底屏蔽"preferExternal"在回退路径上的影响，
     * 即使配置被误改也保证回退方向。</p>
     */
    private Agent pickApiKeyLlmAgent(AgentRole inputRole) {
        final AgentRole role = (inputRole != null) ? inputRole : AgentRole.EXECUTOR;
        return agentService.listActive().stream()
                .filter(a -> a.getAccessType() == AgentAccessType.API_KEY_LLM)
                .filter(a -> a.getStatus() == AgentStatus.ACTIVE)
                .filter(a -> role.equals(a.getRole()))
                .filter(a -> a.getOnlineStatus() == null
                        || a.getOnlineStatus().name().equals("ONLINE")
                        || a.getOnlineStatus().name().equals("IDLE"))
                .max(java.util.Comparator.comparing(
                        Agent::getScore, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .orElse(null);
    }

    @Override
    public void redispatchAssignedTimeout(Long subTaskId, Long originalAgentId, AgentRole role) {
        // V24: 重分配熔断检查
        if (checkReassignCircuitBreaker(subTaskId)) {
            return;
        }
        SubTask subTask = subTaskService.resetToPendingForDispatch(
                subTaskId, Set.of(SubTaskStatus.ASSIGNED));
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_dispatch_prepare",
                AgentRole.SYSTEM,
                originalAgentId,
                Map.of(
                        "trigger", "assigned_timeout",
                        "previousAgentId", originalAgentId));

        // 必须排除 originalAgentId：原 Agent 可能仍在线但静默丢弃，
        // 重分回它只是原地打转。使用 pickAlternative(excludeAgentId, role)
        // 走 AgentSelector 已有的"同角色排除指定 Agent"选人逻辑。
        // V47：任务级约束（executorAgentIds / required_skills）同样作用于重分配。
        AgentSelectionConstraints constraints = resolveConstraints(subTask);
        var preferred = agentSelector.pickAlternative(originalAgentId, role, constraints);
        if (preferred == null) {
            log.warn("ASSIGNED超时回收：无可用候选 Agent: subTaskId={}, role={}, excludeAgentId={}",
                    subTaskId, role != null ? role : "null", originalAgentId);
            return;
        }

        resilientDispatcher.assignNext(preferred.getId(), subTaskId, constraints);
        log.info("ASSIGNED超时已回收: subTaskId={}, originalAgentId={}, newPreferredAgentId={}",
                subTaskId, originalAgentId, preferred.getId());
    }

    // ══════════════════════════════════════════════════════════════
    //  V47（§6.58 P1）：任务级选人约束解析
    // ══════════════════════════════════════════════════════════════

    /**
     * 从子任务所在 Task 构建任务级选人约束（executorAgentIds 白名单 + required_skills 技能）。
     *
     * <p>两者均未声明时返回 null（与旧行为一致，不约束选人）。</p>
     */
    private AgentSelectionConstraints resolveConstraints(SubTask subTask) {
        Task task = loadTask(subTask);
        if (task == null) {
            return null;
        }
        List<Long> executorAgentIds = TaskAgentPolicy.executorAgentIds(task.getAgentPolicy());
        List<String> requiredSkills = task.getRequiredSkills();
        if ((executorAgentIds == null || executorAgentIds.isEmpty())
                && (requiredSkills == null || requiredSkills.isEmpty())) {
            return null;
        }
        return AgentSelectionConstraints.of(executorAgentIds, requiredSkills);
    }

    /** 加载子任务所属 Task 的 agent_policy；Task 不存在返回 null（防御式，与旧行为一致）。 */
    private Map<String, Object> loadAgentPolicy(SubTask subTask) {
        Task task = loadTask(subTask);
        return task != null ? task.getAgentPolicy() : null;
    }

    private Task loadTask(SubTask subTask) {
        if (subTask == null || subTask.getTaskId() == null) {
            return null;
        }
        try {
            return taskService.getById(subTask.getTaskId());
        } catch (Exception e) {
            log.debug("加载 Task 失败（按无约束处理）: taskId={}, err={}", subTask.getTaskId(), e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  V24：重分配熔断 —— 防止无限重分配死循环
    // ══════════════════════════════════════════════════════════════

    /**
     * 检查子任务重分配是否已达熔断阈值。
     *
     * <p>每个重分配入口（{@link #redispatchOfflineSubTask}、
     * {@link #redispatchAssignedTimeout}、{@link #redispatchForFallback}、
     * {@link #dispatchBlockedSubTask}、{@link #dispatchPendingSubTaskAuto}）
     * 在执行前调用本方法。</p>
     *
     * <p>逻辑：
     * <ol>
     *   <li>{@code max-reassign-attempts <= 0} → 熔断禁用，返回 false</li>
     *   <li>子任务不存在或已是终态/死信（DONE/CANCELLED/DEAD_LETTER）→ 返回 true（跳过）</li>
     *   <li>{@code reassign_attempt_count >= max-reassign-attempts}
     *       → 标记子任务为 DEAD_LETTER（死信池，待人工兜底）+ 记录 timeline → 返回 true（熔断）</li>
     *   <li>否则 → 原子累加 {@code reassign_attempt_count} → 返回 false（放行）</li>
     * </ol>
     * </p>
     *
     * <p>V25：熔断后的状态由 CANCELLED 改为 DEAD_LETTER，区分"人工主动取消"与
     * "系统熔断待人工"；死信由 {@link #redispatchDeadLetter} 人工恢复。</p>
     *
     * @param subTaskId 待检查的子任务 ID
     * @return true = 跳过本次重分配（已达熔断阈值或子任务已终态/死信）；false = 继续重分配
     */
    private boolean checkReassignCircuitBreaker(Long subTaskId) {
        int maxAttempts = agentDispatchProperties.getMaxReassignAttempts();
        if (maxAttempts <= 0) {
            // 熔断禁用（逃生口，不推荐生产使用）
            return false;
        }

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            return false;
        }

        // 终态/死信不再重分配（死信只能走 redispatchDeadLetter 人工入口）
        SubTaskStatus currentStatus = subTask.getStatus();
        if (currentStatus == SubTaskStatus.DONE || currentStatus == SubTaskStatus.CANCELLED
                || currentStatus == SubTaskStatus.DEAD_LETTER) {
            log.debug("子任务已终态或死信，跳过重分配: subTaskId={}, status={}", subTaskId, currentStatus);
            return true;
        }

        int currentCount = subTask.getReassignAttemptCount() != null
                ? subTask.getReassignAttemptCount() : 0;

        if (currentCount >= maxAttempts) {
            log.warn("子任务重分配熔断触发: subTaskId={}, reassignAttemptCount={}, maxReassignAttempts={}, 将子任务转入 DEAD_LETTER 死信池待人工兜底",
                    subTaskId, currentCount, maxAttempts);
            try {
                subTaskService.changeStatus(subTaskId, SubTaskStatus.DEAD_LETTER, null,
                        Map.of("dead_letter_reason", "reassign_attempt_exceeded",
                                "reassign_attempt_count", String.valueOf(currentCount),
                                "max_reassign_attempts", String.valueOf(maxAttempts)));
                taskTimelineService.recordEvent(
                        subTask.getTaskId(),
                        subTask.getId(),
                        "sub_task_dead_letter",
                        AgentRole.SYSTEM,
                        null,
                        Map.of(
                                "reason", "reassign_attempt_exceeded",
                                "reassign_attempt_count", currentCount,
                                "max_reassign_attempts", maxAttempts));
            } catch (Exception e) {
                log.error("子任务重分配熔断-转死信失败: subTaskId={}", subTaskId, e);
            }
            return true;
        }

        // 原子累加重分配计数
        subTaskMapper.incrementReassignAttemptCount(subTaskId, OffsetDateTime.now());
        log.debug("子任务重分配计数累加: subTaskId={}, currentCount={}", subTaskId, currentCount);
        return false;
    }
}
