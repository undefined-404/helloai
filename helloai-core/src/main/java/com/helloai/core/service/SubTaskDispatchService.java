package com.helloai.core.service;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
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
}
