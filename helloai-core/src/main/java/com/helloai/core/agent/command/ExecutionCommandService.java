package com.helloai.core.agent.command;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.AgentExecutionRecord;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.ExecutionCommandCreatedEvent;
import com.helloai.core.service.AgentCommandOutboxService;
import com.helloai.core.service.AgentExecutionRecordService;
import com.helloai.core.service.AgentService;
import com.helloai.core.service.SubTaskService;
import com.helloai.core.service.TaskTimelineService;
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
 * <p>当前只负责"生成命令 + 记录命令痕迹"，并按 {@link AgentExecutionProperties.DispatchMode dispatch-mode} 决定
 * 是否发布本地命令事件、是否写入执行命令 Outbox；MQ 投递的实际动作由
 * {@code OutboxRelayTask}（helloai-job）异步消费 Outbox 行完成，从而把调度层和
 * "投递到 MQ"之间切出清晰边界，并把 publisher-confirms/重试节奏这些投递可靠性细节
 * 收敛到 outbox 表上。</p>
 *
 * <p>Phase 2H ②a 变更：MQ / BOTH 分支不再直接调用 {@link ExecutionCommandMqPublisher}，
 * 改为<em>同事务</em>写入 {@code agent_command_outbox} 行——
 * 命令创建与 outbox 行要么一起提交，要么一起回滚；后续 OutboxRelay 周期任务负责真正发 MQ。
 * NONE / EVENT 分支保持零改动，沿用既有 Poller 兜底 / 事务事件路径。</p>
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
    private final AgentCommandOutboxService agentCommandOutboxService;

    /**
     * 为已分配子任务创建执行命令。
     *
     * <p>事务边界：{@code ExecutionCommand / agent_execution_record / agent_command_outbox}
     * 三表同事务写入；NONE 路径不写 outbox，EVENT 路径不写 outbox。</p>
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

        // Phase 2H ②a：按 dispatch-mode 显式分发，与 consumer-mode 完全解耦
        //   NONE  : 只落库（不写 outbox、不发 event），交给 DB Poller 兜底
        //   EVENT : 只发本地 Spring 事件（事务事件，AFTER_COMMIT 异步消费）
        //   MQ    : 只写 agent_command_outbox（PENDING），由 OutboxRelayTask 异步发 MQ
        //   BOTH  : event + outbox 双发，用于 EVENT→MQ 灰度切换过渡
        AgentExecutionProperties.DispatchMode dispatchMode = executionProperties.getDispatchMode();
        if (executionProperties.isDispatchEvent()) {
            applicationEventPublisher.publishEvent(new ExecutionCommandCreatedEvent(command));
        }
        if (executionProperties.isDispatchMq()) {
            // 同事务内写 outbox 行；该方法不带 @Transactional，依赖外层事务边界，
            // 若事务回滚，outbox 行也回滚，不会出现"record 已提交但 outbox 没写"的孤儿。
            ExecutionCommandMqMessage mqMessage = ExecutionCommandMqMessage.from(command);
            agentCommandOutboxService.createPending(command, mqMessage);
            log.info("execution-command.outbox.enqueued eventId={} subTaskId={} agentId={} dispatch-mode={}",
                    eventId, subTaskId, agentId, dispatchMode);
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
