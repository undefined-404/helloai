package com.helloai.core.agent.command;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ExternalAgentFailureTracker;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import com.helloai.core.agent.execution.SubTaskExecutionService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;

/**
 * 执行结果处理器。
 *
 * <p>负责把执行成功/失败结果回写到子任务状态机与时间线，
 * 让 {@link SubTaskExecutionService} 更聚焦“执行本身”，
 * 后续也便于把结果处理独立挂接到 MQ/轮询消费端。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionResultHandler {

    private final SubTaskService subTaskService;
    private final TaskTimelineService taskTimelineService;
    private final ExternalAgentFailureTracker failureTracker;
    private final AgentService agentService;

    @Transactional(rollbackFor = Exception.class)
    public void handleSuccess(Long subTaskId, Long agentId, AgentResult result) {
        ExecutionResultReport report = new ExecutionResultReport();
        report.setSubTaskId(subTaskId);
        report.setAgentId(agentId);
        report.setSource("INTERNAL");
        report.setIdempotencyKey(null);
        report.setSuccess(result.isSuccess());
        report.setExecutorName(result.getExecutorName());
        report.setFinishReason(result.getFinishReason());
        report.setTokenUsage(result.getTokenUsage());
        report.setOutput(result.getOutput());
        report.setError(null);
        handleReport(report);
    }

    @Transactional(rollbackFor = Exception.class)
    public void handleFailure(Long subTaskId, Long agentId, Exception e) {
        ExecutionResultReport report = new ExecutionResultReport();
        report.setSubTaskId(subTaskId);
        report.setAgentId(agentId);
        report.setSource("INTERNAL");
        report.setIdempotencyKey(null);
        report.setSuccess(false);
        report.setExecutorName(null);
        report.setFinishReason(null);
        report.setTokenUsage(null);
        report.setOutput(null);
        report.setError(e != null ? e.getMessage() : "unknown_error");
        handleReport(report);
    }

    @Transactional(rollbackFor = Exception.class)
    public ExecutionResultApplyResult handleReport(ExecutionResultReport report) {
        if (report == null || report.getSubTaskId() == null) {
            ExecutionResultApplyResult r = new ExecutionResultApplyResult();
            r.setApplied(false);
            r.setStatus("invalid_report");
            return r;
        }

        SubTask subTask = subTaskService.getById(report.getSubTaskId());
        if (subTask == null) {
            ExecutionResultApplyResult r = new ExecutionResultApplyResult();
            r.setApplied(false);
            r.setStatus("subtask_not_found");
            return r;
        }

        Map<String, Object> ctx = new HashMap<>(subTask.getContext() != null ? subTask.getContext() : Map.of());
        Object lastExecutionObj = ctx.get("lastExecution");
        if (report.getIdempotencyKey() != null
                && !report.getIdempotencyKey().isBlank()
                && lastExecutionObj instanceof Map<?, ?> lastExecutionMap) {
            Object lastKey = lastExecutionMap.get("idempotencyKey");
            if (report.getIdempotencyKey().equals(lastKey)) {
                ExecutionResultApplyResult r = new ExecutionResultApplyResult();
                r.setApplied(true);
                r.setIdempotent(true);
                r.setStatus("idempotent_duplicate");
                return r;
            }
        }

        if (subTask.getStatus() != SubTaskStatus.IN_PROGRESS) {
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    report.getSubTaskId(),
                    "sub_task_execute_result_discarded",
                    AgentRole.EXECUTOR,
                    report.getAgentId(),
                    safeMap(
                            "reason", "subtask_status_not_in_progress",
                            "currentStatus", subTask.getStatus().name(),
                            "source", report.getSource(),
                            "idempotencyKey", report.getIdempotencyKey(),
                            "success", report.isSuccess()));
            ExecutionResultApplyResult r = new ExecutionResultApplyResult();
            r.setApplied(false);
            r.setStatus("discarded_subtask_status_not_in_progress");
            return r;
        }

        Map<String, Object> last = new HashMap<>();
        last.put("at", OffsetDateTime.now().toString());
        last.put("agentId", report.getAgentId());
        last.put("success", report.isSuccess());
        last.put("source", report.getSource());
        last.put("idempotencyKey", report.getIdempotencyKey());
        last.put("executor", report.getExecutorName());
        last.put("finishReason", report.getFinishReason());
        last.put("tokens", report.getTokenUsage());
        last.put("output", report.getOutput());
        last.put("error", report.getError());
        ctx.put("lastExecution", last);
        subTask.setContext(ctx);
        subTaskService.updateById(subTask);

        if (report.isSuccess()) {
            subTaskService.submit(report.getSubTaskId());
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    report.getSubTaskId(),
                    "sub_task_execute_submit",
                    AgentRole.EXECUTOR,
                    report.getAgentId(),
                    safeMap(
                            "success", true,
                            "source", report.getSource(),
                            "executor", report.getExecutorName(),
                            "tokens", report.getTokenUsage(),
                            "idempotencyKey", report.getIdempotencyKey()));
        } else {
            subTaskService.block(report.getSubTaskId());
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    report.getSubTaskId(),
                    "sub_task_execute_failed",
                    AgentRole.EXECUTOR,
                    report.getAgentId(),
                    safeMap(
                            "success", false,
                            "source", report.getSource(),
                            "error", report.getError(),
                            "idempotencyKey", report.getIdempotencyKey()));
        }

        // N11 阈值回退计数：仅对 CLI_CLIENT Agent 累加/重置。
        // SQL 条件已限定 access_type=CLI_CLIENT，误调 API_KEY_LLM 也不会写库；
        // tracker 内部已做 try/catch + REQUIRES_NEW。
        //
        // 关键：failureTracker 以 REQUIRES_NEW 独立事务更新同一 agent 行，而本事务在成功路径下
        // 已通过 subTaskService.submit() -> changeStatus(REVIEW) -> heartbeatService.active()
        // 锁定了该 agent 行；若在事务内直接调用，会形成"外层持锁 + 内层新事务改同一行"的自死锁。
        // 因此挪到主事务提交后（afterCommit）执行：此时行锁已释放，REQUIRES_NEW 独立语义仍保留。
        Agent targetAgent = report.getAgentId() != null ? agentService.getById(report.getAgentId()) : null;
        if (targetAgent != null && targetAgent.getAccessType() == AgentAccessType.CLI_CLIENT) {
            final Long trackAgentId = report.getAgentId();
            final boolean trackSuccess = report.isSuccess();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        applyFailureTracking(trackAgentId, trackSuccess);
                    }
                });
            } else {
                applyFailureTracking(trackAgentId, trackSuccess);
            }
        }

        ExecutionResultApplyResult r = new ExecutionResultApplyResult();
        r.setApplied(true);
        r.setStatus("applied");
        return r;
    }

    @Data
    public static class ExecutionResultApplyResult {
        private boolean applied;
        private boolean idempotent;
        private String status;
    }

    /**
     * N11 计数写入（成功重置 / 失败累加）。由主事务 afterCommit 回调触发，
     * 确保执行时 agent 行锁已释放，避免与主链路事务自死锁。
     */
    private void applyFailureTracking(Long agentId, boolean success) {
        if (success) {
            failureTracker.recordSuccess(agentId);
        } else {
            failureTracker.recordFailure(agentId);
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
