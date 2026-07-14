package com.helloai.core.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.executor.AgentSelector;
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
}
