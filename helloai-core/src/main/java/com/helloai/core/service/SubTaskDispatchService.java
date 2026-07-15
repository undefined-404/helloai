package com.helloai.core.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
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

    /**
     * 对 BLOCKED 子任务执行重新调度。
     */
    public void dispatchBlockedSubTask(Long subTaskId, Long preferredAgentId) {
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
     */
    public void redispatchOfflineSubTask(Long subTaskId, Long offlineAgentId) {
        SubTask subTask = subTaskService.resetToPendingForDispatch(
                subTaskId, Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.IN_PROGRESS));
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
}
