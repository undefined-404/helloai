package com.helloai.core.task.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.base.NoCandidateAgentException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.RetryPolicy;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.executor.AgentSelector.AgentSelectionConstraints;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.policy.TaskAgentPolicy;
import com.helloai.core.agent.port.TaskDispatchPort;
import com.helloai.core.agent.port.TaskDispatchPort.DispatchConstraints;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
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
    private final TaskDispatchPort taskDispatchPort;
    private final TaskTimelineService taskTimelineService;
    private final AgentSelector agentSelector;
    private final AgentService agentService;
    private final SubTaskMapper subTaskMapper;
    private final AgentDispatchProperties agentDispatchProperties;
    private final TaskService taskService;

    /**
     * 重派退避序列（秒）：按已消耗的 {@code attempt_total} 逐级放大（G-015 B2.2）。
     *
     * <p>背景（v3 实测）：心跳抖动会在 3 分钟内打满 5 次预算直入死信，导致下游 DAG 级联卡死。
     * 加入退避后，第 1 次重派后等 60s、第 2 次后 180s、第 3 次后 600s、第 4 次及以后 1800s，
     * 让瞬时抖动自然恢复而不是立刻耗尽预算。</p>
     *
     * <p><b>退避时钟 = {@code sub_task.last_attempt_time}（V102，2026-10-05 修 804）</b>：
     * 仅由 {@code incrementAttemptTotal} 原子写入，与尝试严格同源。
     * 此前误用 {@code update_time}——而 {@code block()}/{@code resume()}/{@code changeStatus()}/
     * {@code resetToPendingForDispatch()} 都会 {@code updateById} 刷新它，导致「换人」入口
     * 先 {@code block()} 再重派时闸门读到被自身刷新的 now，{@code nextAllowed = now + 600s}
     * 必然拦截（attempt_total ≥ 1 时「换人」100% 静默失败）。</p>
     */
    private static final int[] REASSIGN_BACKOFF_SECONDS = {60, 180, 600, 1800};

    @Override
    public RedispatchResult dispatchBlockedSubTask(Long subTaskId, Long preferredAgentId) {
        // 重分配闸门（熔断 + 退避）：命中则落 sub_task_redispatch_skipped + 返回明确语义，
        // 不再静默 return（2026-10-05 修 804 可观测性）。
        ReassignGateDecision gate = evaluateReassignGate(subTaskId);
        if (gate.blocked()) {
            recordRedispatchSkipped(subTaskId, gate);
            log.info("BLOCKED 重派被闸门拦截，未改动子任务: subTaskId={}, reason={}, nextAllowed={}",
                    subTaskId, gate.reason(), gate.nextAllowed());
            return RedispatchResult.ofSkipped(gate.reason(), gate.nextAllowed());
        }
        accumulateReassignAttempt(subTaskId);
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
        taskDispatchPort.assignNext(preferredAgentId, subTaskId, resolveConstraints(subTask));
        log.info("阻塞子任务重新进入调度: subTaskId={}, preferredAgentId={}", subTaskId, preferredAgentId);
        return RedispatchResult.ofApplied();
    }

    @Override
    public void redispatchOfflineSubTask(Long subTaskId, Long offlineAgentId) {
        // 重分配熔断检查
        if (checkReassignCircuitBreaker(subTaskId)) {
            return;
        }
        SubTask subTask = subTaskService.resetToPendingForDispatch(
                subTaskId, Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS));
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        // 依赖 ready 守卫 —— 未就绪的离线遗留任务不重派，保持 PENDING
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
        // 关键调度节点：离线改派（用户可观测）——独立于通用 dispatch_prepare，
        // 让用户在时间线上直观看到“Agent 心跳丢失 → 自动改派”的调度决策
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_offline_reassign",
                AgentRole.SYSTEM,
                offlineAgentId,
                Map.of("previousAgentId", offlineAgentId));
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
        taskDispatchPort.assignNext(offlineAgentId, subTaskId, resolveConstraints(subTask));
        log.info("离线子任务重新进入调度: subTaskId={}, offlineAgentId={}", subTaskId, offlineAgentId);
    }

    @Override
    public Long dispatchPendingSubTaskAuto(Long subTaskId, AgentRole role) {
        return doDispatchPendingAuto(subTaskId, role, true);
    }

    @Override
    public Long dispatchPendingSubTaskCompensating(Long subTaskId, AgentRole role) {
        // G-015 B2.1：离线重派的补偿路径复用同一套拦截判定但**不重复计数**——
        // 首选 redispatchOfflineSubTask 已消耗本轮预算，二次选人是对同一次重派的补偿尝试，
        // 若也计数会让单轮最多累加 2 次，把 attempt_total 的 5 次预算在 3 轮内打满。
        return doDispatchPendingAuto(subTaskId, role, false);
    }

    /**
     * {@link #dispatchPendingSubTaskAuto} 与 {@link #dispatchPendingSubTaskCompensating} 的共用实现。
     *
     * <p><b>计数时机（2026-10-05 修 P1-1）</b>：{@code accumulateReassignAttempt} 已后移到
     * 「选人成功之后、真正派发之前」——只有真正选出执行者、进入派发才消耗一次重派预算。
     * 「无候选」「子任务不存在」「状态非 PENDING」等<b>未派出</b>的失败不再累加
     * {@code attempt_total}，也不再推进退避时钟 {@code last_attempt_time}（二者同源于
     * {@code incrementAttemptTotal}）⇒ 等待态不会被误烧成熔断死信。</p>
     *
     * @param countAttempt true=消耗一次重派预算（常规入口）；false=只复用拦截判定不计数（补偿路径）
     */
    private Long doDispatchPendingAuto(Long subTaskId, AgentRole role, boolean countAttempt) {
        // 依赖 ready 守卫必须在熔断计数之前 —— 未就绪的 PENDING 子任务
        // 会被定时兜底任务反复扫描，若先累加 reassign_attempt_count 会被误推入死信
        SubTask readyCheck = subTaskService.getById(subTaskId);
        if (readyCheck != null && readyCheck.getStatus() == SubTaskStatus.PENDING
                && !subTaskService.isReady(readyCheck)) {
            log.debug("子任务依赖未就绪，跳过分发（保持 PENDING）: subTaskId={}, dependsOn={}",
                    subTaskId, readyCheck.dependsOnIdList());
            return null;
        }
        // 重分配闸门 —— 封堵定时兜底任务（PendingOrphan / recoverPendingUnassigned /
        // HealthCheck 二次选人）经本入口无限改派的旁路；退避窗口内同样在此拦截
        if (isReassignBlockedOrEscalate(subTaskId)) {
            return null;
        }
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getStatus() != SubTaskStatus.PENDING) {
            throw new BizException("只有 PENDING 状态的子任务才能自动分配: subTaskId=" + subTaskId + ", status=" + subTask.getStatus());
        }

        // 任务级选人约束（executorAgentIds 白名单 + required_skills 技能 AND 匹配）
        DispatchConstraints constraints = resolveConstraints(subTask);
        var preferred = agentSelector.pickPreferred(role, toSelectorConstraints(constraints));
        if (preferred == null) {
            // 无候选属可自愈等待态：抛专用异常且**不消耗**重派预算（见方法 javadoc）
            throw new NoCandidateAgentException("无可用候选 Agent: role=" + role);
        }

        // ★ P1-1 修复：只有真正选出执行者、进入派发，才消耗一次重派预算。
        // 「无候选」「状态非 PENDING」等**未派出**的失败不再累加 attempt_total、
        // 不再推进退避时钟 last_attempt_time ⇒ 不会把等待态误烧成熔断死信。
        if (countAttempt) {
            accumulateReassignAttempt(subTaskId);
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

        taskDispatchPort.assignNext(preferred.getId(), subTaskId, constraints);
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
        AgentProfileSnapshot agent = agentService.getProfileById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        // 人工兜底指派语义 = 选执行者；PLANNER/REVIEWER 不参与执行（与改派候选接口同口径）
        if (agent.role() != AgentRole.EXECUTOR) {
            throw new BizException("死信重派只支持执行者（EXECUTOR）Agent，实际角色: " + agent.role());
        }

        // 清零共享重试预算（attempt_total），重新投入调度链后从头计数
        subTaskMapper.resetAttemptTotal(subTaskId, OffsetDateTime.now());

        // §6.57 语义对齐：人工死信重派 = 用户拍板开启新一轮，与人工驳回（reworkFresh）
        // 一致地重置核验返工计数并清除人工介入标记；否则新执行者提交后仍命中
        // skip_max_rework 跳过自动核验再次入死信（实测：c3gs-r4 重派 inner 新 agent 后
        // reworkCount=3 仍被跳核验，DEAD_LETTER 死循环）。
        subTask.setReworkCount(0);
        Map<String, Object> ctx = new HashMap<>(subTask.getContext() != null
                ? subTask.getContext() : Map.of());
        // G-015 B3.1：清除上一轮死信的残留信号——否则死信重派成功后，
        // getSubTaskDetail / 前端仍会读到过期的 dead_letter_reason（如 reassign_attempt_exceeded），
        // 让执行者误判「这个任务又被判死了」。三个键与 attempt_total 是同批写入的快照，一并清理。
        boolean ctxChanged = ctx.remove("manualIntervention") != null;
        ctxChanged |= ctx.remove("dead_letter_reason") != null;
        ctxChanged |= ctx.remove("attempt_total") != null;
        ctxChanged |= ctx.remove("max_reassign_attempts") != null;
        if (ctxChanged) {
            subTask.setContext(ctx);
        }
        subTaskService.updateById(subTask);

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
                        "agentName", agent.name() != null ? agent.name() : "unknown",
                        "reworkCountReset", true));
        log.info("死信子任务人工兜底指派完成: subTaskId={}, agentId={}", subTaskId, agentId);
    }

    @Override
    public Long redispatchForFallback(Long subTaskId, Long failedAgentId, String reason) {
        // 重分配熔断检查
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
        AgentProfileSnapshot failedAgent = failedAgentId != null ? agentService.getProfileById(failedAgentId) : null;
        final AgentRole role = (failedAgent != null && failedAgent.role() != null)
                ? failedAgent.role() : AgentRole.EXECUTOR;

        // （§6.58 P1）：任务级回退策略约束——fallbackPolicy=NONE 或 difficulty=HIGH
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
        AgentProfileSnapshot fallbackAgent = pickApiKeyLlmAgent(role);

        if (fallbackAgent == null) {
            String msg = String.format(
                    "N11 阈值回退失败：未找到同角色(role=%s) 的 API_KEY_LLM Agent，subTaskId=%d",
                    role, subTaskId);
            log.error(msg);
            throw new BizException(msg);
        }

        // RESTRICTED 回退——仅允许回退到 executorAgentIds 内的 API_KEY_LLM Agent；
        // 集合为空或回退目标不在集合内时等同 NONE，打人工介入标记。
        if (TaskAgentPolicy.fallbackPolicy(agentPolicy) == TaskAgentPolicy.FallbackPolicy.RESTRICTED) {
            List<Long> allowed = TaskAgentPolicy.executorAgentIds(agentPolicy);
            if (allowed.isEmpty() || !allowed.contains(fallbackAgent.id())) {
                taskTimelineService.recordEvent(subTask.getTaskId(), subTask.getId(),
                        "sub_task_fallback_skip_policy", AgentRole.SYSTEM, fallbackAgent.id(),
                        Map.of("reason", "fallback_policy_restricted_not_in_whitelist",
                                "fallbackAgentId", fallbackAgent.id(),
                                "previousAgentId", failedAgentId));
                subTaskService.markManualIntervention(subTaskId, "fallback_skip_policy_restricted",
                        Map.of("failedAgentId", failedAgentId == null ? "" : failedAgentId,
                                "fallbackAgentId", fallbackAgent.id()));
                log.warn("N11 回退跳过：RESTRICTED 策略下回退目标不在执行者白名单, subTaskId={}, fallbackAgentId={}",
                        subTaskId, fallbackAgent.id());
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
            if (!fallbackAgent.localExecutionCapable()) {
                taskTimelineService.recordEvent(subTask.getTaskId(), subTask.getId(),
                        "sub_task_fallback_skip_need_human", AgentRole.SYSTEM, fallbackAgent.id(),
                        Map.of("reason", "execution_dense_no_local_capability",
                                "fallbackAgentId", fallbackAgent.id(),
                                "previousAgentId", failedAgentId));
                subTaskService.markManualIntervention(subTaskId, "fallback_skip_execution_dense",
                        Map.of("failedAgentId", failedAgentId == null ? "" : failedAgentId,
                                "fallbackAgentId", fallbackAgent.id()));
                log.warn("N11 回退跳过：执行密集任务不可回退给无本机能力 Agent, subTaskId={}, fallbackAgentId={}",
                        subTaskId, fallbackAgent.id());
                return null;
            }
        }

        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_dispatch_prepare",
                AgentRole.SYSTEM,
                fallbackAgent.id(),
                Map.of(
                        "trigger", "external_fallback",
                        "preferredAgentId", fallbackAgent.id(),
                        "previousAgentId", failedAgentId,
                        "reason", reason != null ? reason : ""));

        taskDispatchPort.assignNext(fallbackAgent.id(), subTaskId);
        log.info("N11 阈值回退已重新进入调度链: subTaskId={}, failedAgentId={}, fallbackAgentId={}",
                subTaskId, failedAgentId, fallbackAgent.id());
        return fallbackAgent.id();
    }

    /**
     * 在同角色 EXECUTOR/PLANNER/REVIEWER 中按 score 降序选一个
     * access_type=API_KEY_LLM 且 status=ACTIVE 的 Agent。
     *
     * <p>简单实现：基于 {@code AgentService.listActive} + stream filter。
     * 不复用 {@link AgentSelector} 是为了彻底屏蔽"preferExternal"在回退路径上的影响，
     * 即使配置被误改也保证回退方向。</p>
     */
    private AgentProfileSnapshot pickApiKeyLlmAgent(AgentRole inputRole) {
        final AgentRole role = (inputRole != null) ? inputRole : AgentRole.EXECUTOR;
        return agentService.listActiveProfiles().stream()
                .filter(a -> a.accessType() == AgentAccessType.API_KEY_LLM)
                .filter(a -> a.status() == AgentStatus.ACTIVE)
                .filter(a -> role.equals(a.role()))
                .filter(a -> a.onlineStatus() == null
                        || a.onlineStatus().name().equals("ONLINE")
                        || a.onlineStatus().name().equals("IDLE"))
                .max(java.util.Comparator.comparing(
                        AgentProfileSnapshot::score,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .orElse(null);
    }

    @Override
    public void redispatchAssignedTimeout(Long subTaskId, Long originalAgentId, AgentRole role) {
        // 重分配熔断检查
        if (checkReassignCircuitBreaker(subTaskId)) {
            return;
        }
        SubTask subTask = subTaskService.resetToPendingForDispatch(
                subTaskId, Set.of(SubTaskStatus.ASSIGNED));
        // 关键调度节点：超时未领取改派（用户可观测）——独立于通用 dispatch_prepare，
        // 让用户在时间线上直观看到“分配后无人领取 → 自动改派”的调度决策
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_unclaimed_timeout_reassign",
                AgentRole.SYSTEM,
                originalAgentId,
                Map.of(
                        "previousAgentId", originalAgentId,
                        "role", role != null ? role.name() : AgentRole.EXECUTOR.name()));
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
        // 任务级约束（executorAgentIds / required_skills）同样作用于重分配。
        DispatchConstraints constraints = resolveConstraints(subTask);
        var preferred = agentSelector.pickAlternative(originalAgentId, role, toSelectorConstraints(constraints));
        if (preferred == null) {
            log.warn("ASSIGNED超时回收：无可用候选 Agent: subTaskId={}, role={}, excludeAgentId={}",
                    subTaskId, role != null ? role : "null", originalAgentId);
            return;
        }

        taskDispatchPort.assignNext(preferred.getId(), subTaskId, constraints);
        log.info("ASSIGNED超时已回收: subTaskId={}, originalAgentId={}, newPreferredAgentId={}",
                subTaskId, originalAgentId, preferred.getId());
    }

    @Override
    public RedispatchResult redispatchInProgress(Long subTaskId, Long preferredAgentId) {
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        // 状态校验前置（保持既有语义：非人工可处置状态直接报错，不产生任何副作用）
        // ASSIGNED 亦为人工处置窗口：子任务已被指派但尚未开工（外部 Agent 不在线 /
        // 首个分配不当时），此前三个改派入口（reassignById 仅 BLOCKED、
        // redispatchInProgressById 仅 IN_PROGRESS/PAUSED、redispatchDeadLetterById 仅 DEAD_LETTER）
        // 均不收 ASSIGNED ⇒ 人工无法介入，只能干等 dispatch.assigned-timeout-minutes（默认 10 分钟）
        // 自动回收。block() 早已允许 ASSIGNED（SubTaskServiceImpl#block），状态机亦允许
        // ASSIGNED→BLOCKED，故此处放开不涉及状态机改动。
        // 仍受下方重派闸门（熔断 + 退避）统一约束，人工入口不享受特权。
        if (subTask.getStatus() != SubTaskStatus.ASSIGNED
                && subTask.getStatus() != SubTaskStatus.IN_PROGRESS
                && subTask.getStatus() != SubTaskStatus.PAUSED) {
            throw new BizException("只有 ASSIGNED / IN_PROGRESS / PAUSED 状态的子任务才能改派，当前状态: " + subTask.getStatus());
        }

        // 闸门判定提到 block()/resume() 之前（2026-10-05 修 804 修法 4）：
        // block()/resume() 均经 updateById 写 update_time，历史上闸门读 update_time 会被自身
        // 刷新（nextAllowed = now + 600s 必然拦截「换人」）。退避时钟现已改绑 last_attempt_time
        // （updateById 不写），此处仍显式前置判定——一旦被拦：不改动任务状态、不 block，
        // 落 sub_task_redispatch_skipped 并回传明确语义（消除静默 return）。
        ReassignGateDecision gate = evaluateReassignGate(subTaskId);
        if (gate.blocked()) {
            recordRedispatchSkipped(subTaskId, gate);
            log.info("执行停滞改派被闸门拦截，未改动子任务: subTaskId={}, reason={}, nextAllowed={}",
                    subTaskId, gate.reason(), gate.nextAllowed());
            return RedispatchResult.ofSkipped(gate.reason(), gate.nextAllowed());
        }

        if (subTask.getStatus() == SubTaskStatus.PAUSED) {
            // 暂停后换人：先恢复执行权（PAUSED 到 IN_PROGRESS 是状态机允许的转移），
            // 再统一走下方 block + 重调度链。
            subTaskService.resume(subTaskId);
        }
        // 人工判定执行停滞：先报告阻塞（带原因落 timeline + BLOCKED 收件箱通知），
        // 再走既有 BLOCKED 重调度链（熔断计数/选人/fallback 全部复用，语义一致）。
        // 两步各自事务：block 先落库，重派失败时任务停在 BLOCKED 可再人工重试。
        subTaskService.block(subTaskId, "人工判定执行停滞，改派新执行者", null);
        RedispatchResult result = dispatchBlockedSubTask(subTaskId, preferredAgentId);
        log.info("执行停滞改派: subTaskId={}, preferredAgentId={}, applied={}",
                subTaskId, preferredAgentId, result.applied());
        return result;
    }

    // ══════════════════════════════════════════════════════════════
    //  （§6.58 P1）：任务级选人约束解析
    // ══════════════════════════════════════════════════════════════

    /**
     * 从子任务所在 Task 构建任务级选人约束（executorAgentIds 白名单 + required_skills 技能）。
     *
     * <p>两者均未声明时返回 null（与旧行为一致，不约束选人）。</p>
     */
    private DispatchConstraints resolveConstraints(SubTask subTask) {
        Task task = loadTask(subTask);
        if (task == null) {
            return null;
        }
        List<Long> executorAgentIds = TaskAgentPolicy.executorAgentIds(task.getAgentPolicy());
        // G-010：选人技能约束用并集装箱（子任务级 ∪ 任务级）——子任务级指派的技能
        // 同样要求执行者具备；与执行/审查装箱同源（mergeSkills）
        List<String> requiredSkills = subTaskService.mergeSkills(subTask);
        return DispatchConstraints.of(executorAgentIds, requiredSkills);
    }

    /**
     * 端口约束（纯数据）→ AgentSelector 内部约束（含 Agent 实体判定能力）。
     *
     * <p>端口契约 {@link TaskDispatchPort.DispatchConstraints} 不引用 agent 域类型，
     * 而 {@code AgentSelector.pickPreferred/pickAlternative} 需要
     * {@link AgentSelectionConstraints}（allows(Agent) 判定），本方法在 task 域
     * 做一次性适配转换，转换语义与阶段五改造前的构造完全一致。</p>
     */
    private AgentSelectionConstraints toSelectorConstraints(DispatchConstraints constraints) {
        return constraints == null ? null
                : AgentSelectionConstraints.of(constraints.allowedAgentIds(), constraints.requiredSkills());
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
    //  重分配熔断 —— 防止无限重分配死循环
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
     *   <li>{@code attempt_total >= max-reassign-attempts}（共享预算，
     *       判定语义见 {@code RetryPolicy.exceedsMax}）
     *       → 标记子任务为 DEAD_LETTER（死信池，待人工兜底）+ 记录 timeline → 返回 true（熔断）</li>
     *   <li>否则 → 原子累加 {@code attempt_total} → 返回 false（放行）</li>
     * </ol>
     * </p>
     *
     * <p>熔断后的状态由 CANCELLED 改为 DEAD_LETTER，区分"人工主动取消"与
     * "系统熔断待人工"；死信由 {@link #redispatchDeadLetter} 人工恢复。</p>
     *
     * <p><b>2026-10-05 修 804</b>：本入口由周期巡检驱动，拦截时仅对一次性的
     * {@code circuit_open} 落 {@code sub_task_redispatch_skipped} 时间线
     * （backoff 每 tick 命中故不落，避免刷屏）；人工入口另在各自方法内对 backoff 也留痕。</p>
     *
     * @param subTaskId 待检查的子任务 ID
     * @return true = 跳过本次重分配（已达熔断阈值 / 处于退避窗口 / 已终态死信）；false = 继续重分配
     */
    private boolean checkReassignCircuitBreaker(Long subTaskId) {
        ReassignGateDecision gate = evaluateReassignGate(subTaskId);
        if (gate.blocked()) {
            // 可观测性（2026-10-05 修 804）：闸门拦截不再静默。但这几个入口由周期巡检驱动
            // （离线巡检 / 超时回收 / N11 回退），退避窗口内的 backoff 每 tick 都会命中——
            // 若逐次落事件会刷屏 timeline，故系统入口只对**一次性**的 circuit_open 留痕
            // （熔断转死信后子任务即变终态，不重复）。人工入口（redispatchInProgress /
            // dispatchBlockedSubTask）另在各自方法内对 backoff 也留痕。
            if (!"backoff".equals(gate.reason())) {
                recordRedispatchSkipped(subTaskId, gate);
            }
            return true;
        }
        accumulateReassignAttempt(subTaskId);
        return false;
    }

    /**
     * 重派闸门判定（**只判定、不累加预算**）的布尔投影 —— 供周期扫描的自动补偿路径
     * （{@link #dispatchPendingSubTaskAuto}）复用：只需"是否拦截"、不需要拦截原因，
     * 且**不落 skip 时间线**（避免逐 tick 刷屏）。
     *
     * <p>完整判定语义见 {@link #evaluateReassignGate(Long)}。</p>
     *
     * @param subTaskId 待检查的子任务 ID
     * @return true = 跳过本次重分配（已达熔断阈值 / 处于退避窗口 / 已终态死信）
     */
    private boolean isReassignBlockedOrEscalate(Long subTaskId) {
        return evaluateReassignGate(subTaskId).blocked();
    }

    /**
     * 重派闸门决策 —— 判定结果连同「拦截原因 / 退避结束时刻」一并返回，
     * 供调用方落 {@code sub_task_redispatch_skipped} 时间线与回传接口明确语义（2026-10-05 修 804）。
     *
     * @param blocked     true = 拦截本次重派
     * @param reason      拦截原因：{@code terminal}（终态/死信）/ {@code circuit_open}（预算耗尽转死信）
     *                    / {@code backoff}（退避窗口未过）；未拦截时为 null
     * @param nextAllowed 退避窗口结束时刻（仅 {@code reason=backoff} 时非 null）
     * @param taskId      所属主任务 ID（用于落时间线；取不到时为 null）
     */
    private record ReassignGateDecision(boolean blocked, String reason,
                                        OffsetDateTime nextAllowed, Long taskId) {

        static ReassignGateDecision pass(Long taskId) {
            return new ReassignGateDecision(false, null, null, taskId);
        }

        static ReassignGateDecision terminal(Long taskId) {
            return new ReassignGateDecision(true, "terminal", null, taskId);
        }

        static ReassignGateDecision circuitOpen(Long taskId) {
            return new ReassignGateDecision(true, "circuit_open", null, taskId);
        }

        static ReassignGateDecision backoff(Long taskId, OffsetDateTime nextAllowed) {
            return new ReassignGateDecision(true, "backoff", nextAllowed, taskId);
        }
    }

    /**
     * 重派闸门判定（**只判定、不累加预算**）：G-015 B2 起同时承载"熔断"与"退避"两道判断。
     *
     * <p>判定顺序：</p>
     * <ol>
     *   <li>{@code max-reassign-attempts <= 0} → 熔断禁用（逃生口），放行；</li>
     *   <li>子任务不存在 → 放行（交由调用方后续校验）；</li>
     *   <li>已是终态/死信（DONE/CANCELLED/DEAD_LETTER）→ 拦截；</li>
     *   <li>{@code attempt_total >= max-reassign-attempts} → 标记 DEAD_LETTER + timeline，拦截；</li>
     *   <li><b>退避窗口未过</b>（距上次尝试不足 {@link #REASSIGN_BACKOFF_SECONDS} 对应时长）→ 拦截。
     *       与"熔断"的区别：退避**不改变子任务状态**、不消耗预算，只是本轮不重派，窗口过后自然恢复；</li>
     *   <li>否则放行（调用方随后应调用 {@link #accumulateReassignAttempt}）。</li>
     * </ol>
     *
     * <p>之所以把"判定"与"累加"拆开：离线重派的补偿路径
     * （{@link #dispatchPendingSubTaskCompensating}）需要复用同一套拦截判定
     * **但不重复计数**，避免同一轮对同一子任务累加两次、把 {@code attempt_total} 快速打满推向死信。</p>
     *
     * <p><b>退避时钟 = {@code sub_task.last_attempt_time}（V102）</b>——只由 {@code incrementAttemptTotal}
     * 写入，与尝试严格同源；不再读 {@code update_time}（会被 block/resume/changeStatus 刷新，
     * 导致「换人」被自身刷新的时钟拦截）。</p>
     *
     * @param subTaskId 待检查的子任务 ID
     * @return 闸门决策（含拦截原因与退避结束时刻）
     */
    private ReassignGateDecision evaluateReassignGate(Long subTaskId) {
        int maxAttempts = agentDispatchProperties.getMaxReassignAttempts();
        if (maxAttempts <= 0) {
            // 熔断禁用（逃生口，不推荐生产使用）
            return ReassignGateDecision.pass(null);
        }

        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            return ReassignGateDecision.pass(null);
        }
        Long taskId = subTask.getTaskId();

        // 终态/死信不再重分配（死信只能走 redispatchDeadLetter 人工入口）
        SubTaskStatus currentStatus = subTask.getStatus();
        if (currentStatus == SubTaskStatus.DONE || currentStatus == SubTaskStatus.CANCELLED
                || currentStatus == SubTaskStatus.DEAD_LETTER) {
            log.debug("子任务已终态或死信，跳过重分配: subTaskId={}, status={}", subTaskId, currentStatus);
            return ReassignGateDecision.terminal(taskId);
        }

        int currentCount = subTask.getAttemptTotal() != null
                ? subTask.getAttemptTotal() : 0;

        if (RetryPolicy.exceedsMax(currentCount, maxAttempts)) {
            log.warn("子任务重分配熔断触发: subTaskId={}, attemptTotal={}, maxReassignAttempts={}, 将子任务转入 DEAD_LETTER 死信池待人工兜底",
                    subTaskId, currentCount, maxAttempts);
            try {
                subTaskService.changeStatus(subTaskId, SubTaskStatus.DEAD_LETTER, null,
                        Map.of("dead_letter_reason", "reassign_attempt_exceeded",
                                "attempt_total", String.valueOf(currentCount),
                                "max_reassign_attempts", String.valueOf(maxAttempts)));
                taskTimelineService.recordEvent(
                        subTask.getTaskId(),
                        subTask.getId(),
                        "sub_task_dead_letter",
                        AgentRole.SYSTEM,
                        null,
                        Map.of(
                                "reason", "reassign_attempt_exceeded",
                                "attempt_total", currentCount,
                                "max_reassign_attempts", maxAttempts));
            } catch (Exception e) {
                log.error("子任务重分配熔断-转死信失败: subTaskId={}", subTaskId, e);
            }
            return ReassignGateDecision.circuitOpen(taskId);
        }

        // G-015 B2.2 退避窗口：距上次尝试（incrementAttemptTotal 写入的 last_attempt_time）不足本档
        // 退避时长则跳过本轮重派，且不消耗预算。只推迟不终止，窗口过后自然恢复。
        if (currentCount > 0 && subTask.getLastAttemptTime() != null) {
            int backoffSeconds = REASSIGN_BACKOFF_SECONDS[
                    Math.min(currentCount - 1, REASSIGN_BACKOFF_SECONDS.length - 1)];
            OffsetDateTime nextAllowed = subTask.getLastAttemptTime().plusSeconds(backoffSeconds);
            if (OffsetDateTime.now().isBefore(nextAllowed)) {
                log.debug("子任务处于重派退避窗口，跳过本轮重派: subTaskId={}, attemptTotal={}, backoffSeconds={}, nextAllowed={}",
                        subTaskId, currentCount, backoffSeconds, nextAllowed);
                return ReassignGateDecision.backoff(taskId, nextAllowed);
            }
        }
        return ReassignGateDecision.pass(taskId);
    }

    /**
     * 落 {@code sub_task_redispatch_skipped} 时间线（2026-10-05 修 804 可观测性）。
     *
     * <p>仅对**非终态**的闸门拦截留痕（{@code backoff} / {@code circuit_open}）——
     * 终态 / 死信拦截属正常流转不记。</p>
     *
     * <p><b>调用点与频控</b>：</p>
     * <ul>
     *   <li>人工入口 {@link #redispatchInProgress}、{@link #dispatchBlockedSubTask}：
     *       backoff 与 circuit_open 均留痕（用户点一次落一条，正是要消除的「静默」）；</li>
     *   <li>系统入口（经 {@link #checkReassignCircuitBreaker} 的离线巡检 / 超时回收 / N11 回退）：
     *       只对一次性的 circuit_open 留痕；backoff 由周期巡检反复命中，若逐次落事件会刷屏 timeline；</li>
     *   <li>周期自动补偿 {@link #dispatchPendingSubTaskAuto}：完全不落（只读布尔判定）。</li>
     * </ul>
     */
    private void recordRedispatchSkipped(Long subTaskId, ReassignGateDecision gate) {
        String reason = gate.reason();
        if (reason == null || "terminal".equals(reason) || gate.taskId() == null) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("reason", reason);
            payload.put("subTaskId", subTaskId);
            if (gate.nextAllowed() != null) {
                payload.put("nextAllowed", gate.nextAllowed().toString());
            }
            taskTimelineService.recordEvent(
                    gate.taskId(),
                    subTaskId,
                    "sub_task_redispatch_skipped",
                    AgentRole.SYSTEM,
                    null,
                    payload);
        } catch (Exception e) {
            log.warn("重派拦截 timeline 写入失败（不影响主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    /**
     * 原子累加共享重试预算（{@code attempt_total} 替代 {@code reassign_attempt_count}）。
     *
     * <p>与 {@link #isReassignBlockedOrEscalate} 分离，供"复用判定但不重复计数"的补偿路径
     * （{@link #dispatchPendingSubTaskCompensating}）选择调用。</p>
     */
    private void accumulateReassignAttempt(Long subTaskId) {
        subTaskMapper.incrementAttemptTotal(subTaskId, OffsetDateTime.now());
        log.debug("子任务重分配计数累加: subTaskId={}", subTaskId);
    }
}
