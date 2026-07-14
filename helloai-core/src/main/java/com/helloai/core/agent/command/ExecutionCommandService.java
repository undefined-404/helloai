package com.helloai.core.agent.command;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.AgentExecutionRecord;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.ExecutionCommandCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
 * <p>当前只负责“生成命令 + 记录命令痕迹”，并按 {@link AgentExecutionProperties.DispatchMode dispatch-mode} 决定
 * 是否发布本地命令事件、是否投递 RabbitMQ，不在这里直接触发平台执行，从而把调度层和执行层之间切出清晰边界。</p>
 *
 * <p>Phase 2E 变更：将生产端分发从 {@code consumer-mode} 上摧开，改为读取对称的
 * {@code helloai.execution.dispatch-mode}，彻底解耦"消费侧配置"与"生产侧行为"。</p>
 */
@Slf4j
@Service
public class ExecutionCommandService {

    private final SubTaskService subTaskService;
    private final AgentService agentService;
    private final AgentExecutionRecordService agentExecutionRecordService;
    private final TaskTimelineService taskTimelineService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final AgentExecutionProperties executionProperties;
    private final ObjectProvider<ExecutionCommandMqPublisher> mqPublisherProvider;

    public ExecutionCommandService(SubTaskService subTaskService,
                                   AgentService agentService,
                                   AgentExecutionRecordService agentExecutionRecordService,
                                   TaskTimelineService taskTimelineService,
                                   ApplicationEventPublisher applicationEventPublisher,
                                   AgentExecutionProperties executionProperties,
                                   ObjectProvider<ExecutionCommandMqPublisher> mqPublisherProvider) {
        this.subTaskService = subTaskService;
        this.agentService = agentService;
        this.agentExecutionRecordService = agentExecutionRecordService;
        this.taskTimelineService = taskTimelineService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.executionProperties = executionProperties;
        this.mqPublisherProvider = mqPublisherProvider;
    }

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

        // Phase 2E: 按 dispatch-mode 显式分发，与 consumer-mode 完全解耦
        //   NONE  : 只落库，交给 DB Poller 兜底（当前默认）
        //   EVENT : 只发本地 Spring 事件（旧 EVENT 路径）
        //   MQ    : 只投递 RabbitMQ，Publisher 不可用 → fail-fast
        //   BOTH  : 事件 + MQ 双发，用于灰度过渡
        AgentExecutionProperties.DispatchMode dispatchMode = executionProperties.getDispatchMode();
        if (executionProperties.isDispatchEvent()) {
            applicationEventPublisher.publishEvent(new ExecutionCommandCreatedEvent(command));
        }
        if (executionProperties.isDispatchMq()) {
            ExecutionCommandMqPublisher mqPublisher = mqPublisherProvider.getIfAvailable();
            if (mqPublisher == null) {
                // 启动期 ExecutionDispatchValidator 已抦截；这里是运行期的第二道防线，不隐式回退
                throw new IllegalStateException(
                        "helloai.execution.dispatch-mode=" + dispatchMode
                                + " 但 ExecutionCommandMqPublisher Bean 不可用，请同时设置 helloai.mq.execution-command.producer-enabled=true");
            }
            mqPublisher.publish(command);
        }
        if (dispatchMode == AgentExecutionProperties.DispatchMode.NONE) {
            log.debug("执行命令已创建（dispatch-mode=NONE，只落库，交给 Poller 兜底）: subTaskId={}, recordId={}",
                    subTaskId, record.getId());
        }
        log.info("执行命令已创建: subTaskId={}, agentId={}, recordId={}, trigger={}, dispatch-mode={}, consumer-mode={}",
                subTaskId, agentId, record.getId(), trigger, dispatchMode, executionProperties.getConsumerMode());
        return command;
    }
}
