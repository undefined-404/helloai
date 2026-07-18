package com.helloai.core.agent.mqconsumer;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.ExecutionCommandCreatedEvent;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.command.ExecutionResultReport;
import com.helloai.core.agent.execution.SubTaskExecutionService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * 平台内执行命令消费者。
 *
 * <p>{@link #consume(ExecutionCommand)} 是与命令来源无关的实际消费入口，供 DB Poller 直接调用；
 * {@link #onCommandCreated(ExecutionCommandCreatedEvent)} 仅作为 EVENT / BOTH 模式的本地事务事件适配器。
 * POLLER 模式下 {@code ExecutionCommandService} 不发布事件，因此不会进入事件适配器，
 * 但本 Bean 必须保留，供 Poller 完成实际消费。</p>
 *
 * <p>消费侧职责分层（对齐架构设计参考 §3.1 调度分离）：</p>
 * <ol>
 *     <li>加载 subTask / agent，做一致性校验</li>
 *     <li>{@link SubTaskExecutionService#startIfNeeded} 推进 subTask 到 IN_PROGRESS</li>
 *     <li>{@link AgentExecutionRecordService#markRunning} CAS 执行记录 PENDING→RUNNING</li>
 *     <li>记录消费阶段 timeline</li>
 *     <li>{@link SubTaskExecutionService#executeOnce} 纯执行（不做回写）</li>
 *     <li>{@link ExecutionResultHandler#handleSuccess} / {@link ExecutionResultHandler#handleFailure} 回写</li>
 *     <li>{@link AgentExecutionRecordService#markSuccess} / {@link AgentExecutionRecordService#markFailed} CAS 执行记录</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalExecutionCommandConsumer implements ExecutionCommandConsumer {

    private final SubTaskExecutionService subTaskExecutionService;
    private final AgentExecutionRecordService agentExecutionRecordService;
    private final TaskTimelineService taskTimelineService;
    private final SubTaskService subTaskService;
    private final AgentService agentService;
    private final ExecutionResultHandler executionResultHandler;

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
        if (command.getSubTaskId() == null) {
            log.warn("执行命令消费跳过：subTaskId 为空");
            return;
        }
        if (command.getAgentId() == null) {
            log.warn("执行命令消费跳过：agentId 为空");
            return;
        }

        // 1. 加载 subTask + agent，做一致性校验
        SubTask subTask = subTaskService.getById(command.getSubTaskId());
        if (subTask == null) {
            log.warn("执行命令消费跳过：subTask 不存在 subTaskId={}", command.getSubTaskId());
            return;
        }
        if (subTask.getAssignedAgent() == null) {
            log.warn("执行命令消费跳过：subTask 未分配 Agent subTaskId={}", command.getSubTaskId());
            return;
        }
        if (!command.getAgentId().equals(subTask.getAssignedAgent())) {
            log.warn("执行命令消费跳过：command.agentId={} 与 subTask.assignedAgent={} 不匹配",
                    command.getAgentId(), subTask.getAssignedAgent());
            return;
        }
        Agent agent = agentService.getById(subTask.getAssignedAgent());
        if (agent == null) {
            log.warn("执行命令消费跳过：Agent 不存在 agentId={}", subTask.getAssignedAgent());
            return;
        }

        // 2. 推进 subTask 到 IN_PROGRESS（前置状态推进）
        try {
            subTaskExecutionService.startIfNeeded(subTask.getId(), subTask.getStatus());
        } catch (BizException e) {
            // subTask 状态不允许执行（例如 DONE / CANCELLED），记录 timeline 后跳过
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    command.getSubTaskId(),
                    "sub_task_execution_command_consume_skipped",
                    AgentRole.EXECUTOR,
                    command.getAgentId(),
                    safeMap("reason", "startIfNeeded_rejected", "error", e.getMessage()));
            log.warn("执行命令消费跳过：startIfNeeded 拒绝 subTaskId={}, status={}, error={}",
                    command.getSubTaskId(), subTask.getStatus(), e.getMessage());
            return;
        }

        // 3. 执行记录 CAS：PENDING → RUNNING
        if (command.getRecordId() != null) {
            if (!agentExecutionRecordService.markRunning(command.getRecordId())) {
                log.warn("跳过执行(记录已非 PENDING): recordId={}", command.getRecordId());
                return;
            }
        }

        // 4. 记录消费阶段 timeline
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                command.getSubTaskId(),
                "sub_task_execution_command_consume",
                AgentRole.EXECUTOR,
                command.getAgentId(),
                safeMap(
                        "trigger", command.getTrigger(),
                        "recordId", command.getRecordId(),
                        "eventId", command.getEventId(),
                        "accessType", command.getAccessType() != null ? command.getAccessType().name() : "UNKNOWN"));
        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                command.getSubTaskId(),
                "sub_task_execute_start",
                AgentRole.EXECUTOR,
                command.getAgentId(),
                Map.of("executor", "platform"));

        // 5. 纯执行 + 6. 结果回写
        try {
            AgentResult result = subTaskExecutionService.executeOnce(subTask, agent);
            ExecutionResultReport report = new ExecutionResultReport();
            report.setSubTaskId(command.getSubTaskId());
            report.setAgentId(command.getAgentId());
            report.setSource("INTERNAL");
            report.setIdempotencyKey(command.getEventId());
            report.setSuccess(result.isSuccess());
            report.setExecutorName(result.getExecutorName());
            report.setFinishReason(result.getFinishReason());
            report.setTokenUsage(result.getTokenUsage());
            report.setOutput(result.getOutput());
            report.setError(null);
            executionResultHandler.handleReport(report);
            if (command.getRecordId() != null) {
                if (!agentExecutionRecordService.markSuccess(command.getRecordId())) {
                    log.warn("SUCCESS 写入被拒绝(记录已超时补偿): recordId={}", command.getRecordId());
                }
            }
            log.info("执行命令消费成功: subTaskId={}, agentId={}, recordId={}",
                    command.getSubTaskId(), command.getAgentId(), command.getRecordId());
        } catch (Exception e) {
            // 5'. 失败时记录 LLM 调用失败 timeline（executeOnce 自身只记录 start/end）
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    command.getSubTaskId(),
                    "sub_task_llm_call_failed",
                    AgentRole.EXECUTOR,
                    command.getAgentId(),
                    Map.of("agentId", command.getAgentId(), "error", e.getMessage()));
            ExecutionResultReport report = new ExecutionResultReport();
            report.setSubTaskId(command.getSubTaskId());
            report.setAgentId(command.getAgentId());
            report.setSource("INTERNAL");
            report.setIdempotencyKey(command.getEventId());
            report.setSuccess(false);
            report.setExecutorName(null);
            report.setFinishReason(null);
            report.setTokenUsage(null);
            report.setOutput(null);
            report.setError(e.getMessage());
            executionResultHandler.handleReport(report);
            if (command.getRecordId() != null) {
                if (!agentExecutionRecordService.markFailed(command.getRecordId(), e.getMessage())) {
                    log.warn("FAILED 写入被拒绝(记录已超时补偿): recordId={}", command.getRecordId());
                }
            }
            log.error("执行命令消费失败: subTaskId={}, agentId={}, recordId={}",
                    command.getSubTaskId(), command.getAgentId(), command.getRecordId(), e);
            // 不再 rethrow：handleFailure 已将子任务推进到 BLOCKED，本层只需确保执行记录标记 FAILED
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
