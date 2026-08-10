package com.helloai.core.task.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.helloai.core.agent.dispatcher.ResilientDispatcher;

/**
 * 子任务调度分配服务。
 *
 * <p>负责把“需要重新进入分配链”的场景统一收口到
 * {@link ResilientDispatcher}，避免 Controller、补偿任务直接改库后绕开
 * ASSIGNED 事件、收件箱通知与自动执行链。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskDispatchService {

    private final SubTaskService subTaskService;
    private final ResilientDispatcher resilientDispatcher;
    private final TaskTimelineService taskTimelineService;
    private final AgentSelector agentSelector;
    private final AgentService agentService;
    private final SubTaskMapper subTaskMapper;
    private final AgentDispatchProperties agentDispatchProperties;

    /**
     * 对 BLOCKED 子任务执行重新调度。
     */
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
        resilientDispatcher.assignNext(preferredAgentId, subTaskId);
        log.info("阻塞子任务重新进入调度: subTaskId={}, preferredAgentId={}", subTaskId, preferredAgentId);
    }

    /**
     * 对离线 Agent 遗留子任务执行重新调度。
     *
     * <p>这里故意把离线 Agent 作为首选目标交给 {@link ResilientDispatcher}，
     * 由其 fast-fail + fallback 选择替代 Agent，保持角色与熔断逻辑一致。</p>
     *
     * <p>V27.1 依赖守卫：与 {@link #dispatchPendingSubTaskAuto} 对齐——离线重分配
     * 曾绕过依赖检查，导致"依赖未 DONE 的子任务被直接重派执行"（实测：trae 离线
     * 时依赖 REVIEW 的子任务被直接分给无本机能力的 inner 执行）。未就绪时保持
     * PENDING，等依赖 DONE 后由正常自动分发链接管。</p>
     */
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
        resilientDispatcher.assignNext(offlineAgentId, subTaskId);
        log.info("离线子任务重新进入调度: subTaskId={}, offlineAgentId={}", subTaskId, offlineAgentId);
    }

    /**
     * 初始分配：对 PENDING 子任务执行自动选人并进入弹性调度链。
     *
     * <p>该入口用于“初始分配也按外部优先选人”的目标态演进：
     * 先按角色/策略挑选首选 Agent，再交给 {@link ResilientDispatcher#assignNext(Long, Long)}
     * 执行 fast-fail + 熔断 + fallback 的最终分配。</p>
     *
     * @param subTaskId 子任务 ID
     * @param role      期望角色（通常为 EXECUTOR）
     * @return 实际采用的首选 Agent ID（注意：若首选 fast-fail，最终可能由 fallback 选择其他 Agent）
     */
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

        var preferred = agentSelector.pickPreferred(role);
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

        resilientDispatcher.assignNext(preferred.getId(), subTaskId);
        log.info("子任务自动分配进入调度链: subTaskId={}, preferredAgentId={}, role={}",
                subTaskId, preferred.getId(), role);
        return preferred.getId();
    }

    /**
     * 死信人工兜底：将 DEAD_LETTER 子任务直接指派给指定 Agent（V25）。
     *
     * <p>重分配熔断触发后子任务进入 DEAD_LETTER 死信池，自动调度链不再接触。
     * 本方法是唯一的死信恢复入口，由人工确认目标 Agent 后调用：
     * <ol>
     *   <li>校验子任务当前状态必须为 DEAD_LETTER；</li>
     *   <li>清零 reassign_attempt_count（重新投入调度链后计数从头开始）；</li>
     *   <li>直接 changeStatus → ASSIGNED（与现有手动指派语义一致，
     *       changeStatus 内部自带 outbox 事件 + 收件箱通知 + 自动执行链）；</li>
     *   <li>写 task_timeline 审计事件 sub_task_dead_letter_manual_assign。</li>
     * </ol>
     * </p>
     *
     * <p>人工兜底路径不做在线/心跳拦截：人工判断优先，与现有
     * POST /sub-tasks 带 assignedAgent 的手动指派口径保持一致。</p>
     *
     * @param subTaskId 死信子任务 ID
     * @param agentId   人工指定的目标 Agent ID
     * @throws BizException 子任务不存在或状态不是 DEAD_LETTER 时抛出
     */
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

    /**
     * N11 外部 Agent 阈值回退入口。
     *
     * <p>由 {@code ExternalAgentFallbackTask} 在 CLI_CLIENT Agent
     * 连续失败达到阈值后调用：
     * <ol>
     *   <li>把子任务重置为 PENDING（清空原 assignedAgent）</li>
     *   <li>在同角色 EXECUTOR 中按 score 降序选一个 API_KEY_LLM 类型的活跃 Agent；</li>
     *   <li>把"原失败 Agent"和"新选中的 LLM Agent"都写入 task_timeline 审计；</li>
     *   <li>交给 {@link ResilientDispatcher#assignNext} 做 fast-fail + 熔断 + fallback
     *       收口，避免 Controller / 补偿任务绕开主调度链。</li>
     * </ol>
     * </p>
     *
     * <p>为什么不直接复用 {@link #dispatchPendingSubTaskAuto}？因为
     * auto 走 {@code AgentSelector.pickPreferred}，仍然可能被
     * {@code preferExternal=true} 选回 CLI_CLIENT，违反"N11 强制回退到 LLM"的语义。
     * 本方法绕过 Selector，直接查询同角色 API_KEY_LLM Agent。</p>
     *
     * @param subTaskId       待重新分发的子任务 ID
     * @param failedAgentId   触发回退的 CLI_CLIENT Agent ID（仅用于审计，不参与实际选人）
     * @param reason          触发回退的原因（用于 task_timeline.payload）
     * @return 实际采用的新 Agent ID（回退后用哪个 API_KEY_LLM Agent 接替）
     * @throws BizException 找不到 API_KEY_LLM 候选时抛出
     */
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
        Agent fallbackAgent = pickApiKeyLlmAgent(role);

        if (fallbackAgent == null) {
            String msg = String.format(
                    "N11 阈值回退失败：未找到同角色(role=%s) 的 API_KEY_LLM Agent，subTaskId=%d",
                    role, subTaskId);
            log.error(msg);
            throw new BizException(msg);
        }

        // §6.52 能力预检：执行密集任务（需本机 shell/文件/服务操作）不自动回退给无本机能力的
        // API_KEY_LLM，避免"无能力执行 → 交付物不达标 → 返工循环 → 卡死审核"。改停留原状态
        // 并标记人工介入：外部 agent 回线后可由既有重调度/claim 路径接回，或用户在前端人工改派。
        if (agentDispatchProperties.isFallbackSkipExecutionDense() && isExecutionDense(subTask)) {
            if (isManualInterventionMarked(subTask)) {
                log.debug("人工介入标记已存在，跳过重复回退: subTaskId={}", subTaskId);
                return null;
            }
            if (!hasLocalExecutionCapability(fallbackAgent)) {
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

    /**
     * §6.52 执行密集信号：内容/验收/交付物含本机操作关键词（脚本、服务启动、容器等）。
     *
     * <p>V27.1 起为 public static：供 {@code ResilientDispatcher}（分配入口统一预检）
     * 与 {@code SubTaskReviewService}（审核侧兜底）复用，避免各入口各自实现导致判定不一致。</p>
     */
    private static final Pattern EXECUTION_DENSE_PATTERN = Pattern.compile(
            "(?i)(\\.ps1|\\.sh|\\.bat|\\.py|\\.jar)\\b|docker|kubectl|npm run|mvn |gradle "
                    + "|启动服务|启动应用|执行脚本|运行脚本|部署");

    /** §6.52 执行密集任务判定：内容/验收/交付物含本机操作信号时视为需要本机能力。 */
    public static boolean isExecutionDense(SubTask subTask) {
        String text = String.join("\n",
                nvl(subTask.getContent()), nvl(subTask.getAcceptance()), nvl(subTask.getDeliverable()));
        return EXECUTION_DENSE_PATTERN.matcher(text).find();
    }

    /** §6.52 本机执行能力判定：CLI_CLIENT/WEB_BROWSER 天然可本机操作；API_KEY_LLM 需 capabilities.supportsMCP=true。 */
    public static boolean hasLocalExecutionCapability(Agent agent) {
        if (agent == null || agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
            return true;
        }
        Object supportsMcp = agent.getCapabilities() != null
                ? agent.getCapabilities().get("supportsMCP") : null;
        return Boolean.TRUE.equals(supportsMcp);
    }

    /** §6.52 是否已有人工介入标记（防定时兜底反复触发回退）。 */
    public static boolean isManualInterventionMarked(SubTask subTask) {
        return subTask.getContext() != null && subTask.getContext().containsKey("manualIntervention");
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    /**
     * ASSIGNED 超时回收：长时间无人 claim 的 ASSIGNED 子任务自动回收到 PENDING 并重新调度。
     *
     * <p>P0 可靠性缺口：任务 ASSIGNED 给 Agent 后，如果该 Agent 长时间不 claim
     *（断连、静默丢弃、Bug），任务就永远卡在 ASSIGNED。该方法将超时任务回收
     * 到 PENDING 并重新进入完整调度链（选人 → 分配 → 通知 → 自动执行）。</p>
     *
     * <p>与其它重分配入口的区别：
     * <ul>
     *   <li>{@link #dispatchBlockedSubTask} —— 对 BLOCKED 子任务按 preferredAgentId 重试</li>
     *   <li>{@link #redispatchOfflineSubTask} —— Agent 被判离线后回收其 ASSIGNED/IN_PROGRESS 子任务</li>
     *   <li>本方法 —— ASSIGNED 后长时间无人 claim，原 Agent 可能仍在线但静默丢弃</li>
     * </ul>
     * </p>
     *
     * @param subTaskId       超时的子任务 ID
     * @param originalAgentId 原分配的 Agent ID（用于审计）
     * @param role            期望角色（用于重新选人，null 时回退 EXECUTOR）
     */
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
        var preferred = agentSelector.pickAlternative(originalAgentId, role);
        if (preferred == null) {
            log.warn("ASSIGNED超时回收：无可用候选 Agent: subTaskId={}, role={}, excludeAgentId={}",
                    subTaskId, role != null ? role : "null", originalAgentId);
            return;
        }

        resilientDispatcher.assignNext(preferred.getId(), subTaskId);
        log.info("ASSIGNED超时已回收: subTaskId={}, originalAgentId={}, newPreferredAgentId={}",
                subTaskId, originalAgentId, preferred.getId());
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
