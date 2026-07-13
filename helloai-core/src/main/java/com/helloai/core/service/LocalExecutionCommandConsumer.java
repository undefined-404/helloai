package com.helloai.core.service;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.ExecutionCommandCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地事件版执行命令消费者。
 *
 * <p>当前先用 Spring 事务事件把“命令创建”和“命令消费”分离开：
 * 调度侧只负责创建命令，消费侧在事务提交后异步接管执行。
 * 后续若切到 DB poller 或 MQ，只需要替换本类的事件来源即可。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalExecutionCommandConsumer implements ExecutionCommandConsumer {

    private final SubTaskExecutionService subTaskExecutionService;
    private final AgentExecutionRecordService agentExecutionRecordService;
    private final TaskTimelineService taskTimelineService;
    private final SubTaskService subTaskService;

    @Async("executionCommandExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCreated(ExecutionCommandCreatedEvent event) {
        consume(event.getCommand());
    }

    @Override
    public void consume(ExecutionCommand command) {
        if (command == null) {
            log.warn("执行命令消费跳过：command 为空");
            return;
        }

        Long taskId = null;
        if (command.getSubTaskId() != null) {
            SubTask subTask = subTaskService.getById(command.getSubTaskId());
            if (subTask != null) {
                taskId = subTask.getTaskId();
            }
        }

        if (command.getRecordId() != null) {
            if (!agentExecutionRecordService.markRunning(command.getRecordId())) {
                log.warn("跳过执行(记录已非 PENDING): recordId={}", command.getRecordId());
                return;
            }
        }
        taskTimelineService.recordEvent(
                taskId,
                command.getSubTaskId(),
                "sub_task_execution_command_consume",
                AgentRole.EXECUTOR,
                command.getAgentId(),
                safeMap(
                        "trigger", command.getTrigger(),
                        "recordId", command.getRecordId(),
                        "eventId", command.getEventId(),
                        "accessType", command.getAccessType() != null ? command.getAccessType().name() : "UNKNOWN"));

        try {
            subTaskExecutionService.executeCommand(command);
            if (command.getRecordId() != null) {
                if (!agentExecutionRecordService.markSuccess(command.getRecordId())) {
                    log.warn("SUCCESS 写入被拒绝(记录已超时补偿): recordId={}", command.getRecordId());
                }
            }
            log.info("执行命令消费成功: subTaskId={}, agentId={}, recordId={}",
                    command.getSubTaskId(), command.getAgentId(), command.getRecordId());
        } catch (Exception e) {
            if (command.getRecordId() != null) {
                if (!agentExecutionRecordService.markFailed(command.getRecordId(), e.getMessage())) {
                    log.warn("FAILED 写入被拒绝(记录已超时补偿): recordId={}", command.getRecordId());
                }
            }
            log.error("执行命令消费失败: subTaskId={}, agentId={}, recordId={}",
                    command.getSubTaskId(), command.getAgentId(), command.getRecordId(), e);
            // 不再 rethrow：executeOnce() 内部 handleFailure 已将子任务推进到 BLOCKED，本层只需确保执行记录标记 FAILED
        }
    }

    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key instanceof String keyString) {
                result.put(keyString, keyValues[i + 1]);
            }
        }
        return result;
    }
}
