package com.helloai.core.agent.command;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
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
import com.helloai.core.service.AgentExecutionRecordService;
import com.helloai.core.service.AgentService;
import com.helloai.core.service.SubTaskService;
import com.helloai.core.service.TaskTimelineService;

/**
 * 执行命令服务。
 *
 * <p>当前只负责“生成命令 + 记录命令痕迹”，并按消费模式决定是否发布本地命令创建事件，
 * 不在这里直接触发平台执行，从而把调度层和执行层之间切出清晰边界。</p>
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
    private final AgentExecutionProperties executionProperties;

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
            log.warn("跳过创建执行命令：子任务已有进行中的执行记录: subTaskId={}, agentId={}, trigger={}",
                    subTaskId, agentId, trigger);
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
        AgentExecutionRecord record = agentExecutionRecordService.createPending(
                eventId, subTaskId, agentId, agent.getAccessType(), trigger);

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

        // EVENT / BOTH 模式发布事务事件；POLLER 模式只保留已落库的 PENDING 命令
        if (executionProperties.isEventMode()) {
            applicationEventPublisher.publishEvent(new ExecutionCommandCreatedEvent(command));
        } else {
            log.debug("执行命令已创建（POLLER 主消费模式，跳过 publishEvent）: subTaskId={}, recordId={}",
                    subTaskId, record.getId());
        }
        log.info("执行命令已创建: subTaskId={}, agentId={}, recordId={}, trigger={}, consumer-mode={}",
                subTaskId, agentId, record.getId(), trigger, executionProperties.getConsumerMode());
        return command;
    }
}
