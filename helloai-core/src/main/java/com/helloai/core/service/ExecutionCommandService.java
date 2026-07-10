package com.helloai.core.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.AgentExecutionRecord;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.ExecutionCommandCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 执行命令服务。
 *
 * <p>当前只负责“生成命令 + 记录命令痕迹 + 发布命令创建事件”，
 * 不在这里直接触发平台执行，从而把调度层和执行层之间先切出清晰边界。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionCommandService {

    private final SubTaskService subTaskService;
    private final AgentService agentService;
    private final AgentExecutionRecordService agentExecutionRecordService;
    private final TaskTimelineService taskTimelineService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 为已分配子任务创建执行命令。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExecutionCommand createAssignedCommand(Long subTaskId, Long agentId, String trigger) {
        // 先锁定子任务，再做二次判重与命令落库，避免并发重复发命令。
        SubTask subTask = subTaskService.getByIdForUpdate(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (subTask.getAssignedAgent() == null) {
            throw new BizException("子任务未分配 Agent: " + subTaskId);
        }
        if (!subTask.getAssignedAgent().equals(agentId)) {
            throw new BizException("子任务分配 Agent 不匹配: subTaskId=" + subTaskId
                    + ", assigned=" + subTask.getAssignedAgent()
                    + ", commandAgent=" + agentId);
        }
        if (agentExecutionRecordService.hasPendingOrRunning(subTaskId)) {
            throw new BizException("子任务已有进行中的执行记录: " + subTaskId);
        }
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        if (agent.getAccessType() == null) {
            throw new BizException("Agent 接入类型缺失: " + agentId);
        }

        String eventId = UUID.randomUUID().toString().replace("-", "");
        AgentExecutionRecord record = agentExecutionRecordService.createPending(eventId, subTaskId);

        ExecutionCommand command = ExecutionCommand.builder()
                .recordId(record.getId())
                .eventId(eventId)
                .subTaskId(subTaskId)
                .agentId(agentId)
                .trigger(trigger)
                .accessType(agent.getAccessType())
                .build();

        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTaskId,
                "sub_task_execution_command_created",
                AgentRole.SYSTEM,
                agentId,
                Map.of(
                        "trigger", trigger,
                        "recordId", record.getId(),
                        "eventId", eventId,
                        "accessType", agent.getAccessType().name()));

        applicationEventPublisher.publishEvent(new ExecutionCommandCreatedEvent(command));
        log.info("执行命令已创建: subTaskId={}, agentId={}, recordId={}, trigger={}",
                subTaskId, agentId, record.getId(), trigger);
        return command;
    }
}
