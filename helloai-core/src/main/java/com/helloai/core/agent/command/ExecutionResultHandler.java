package com.helloai.core.agent.command;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.shared.event.SubTaskSubmittedForReviewEvent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.ExternalAgentFailureTracker;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConversationService conversationService;

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

        // 对话流增量副本：执行产出/失败原因写入 conversation_message，
        // INTERNAL/EXTERNAL 上报共用本入口；REQUIRES_NEW 独立事务 + try/catch，失败不阻断主链路
        try {
            if (report.isSuccess()) {
                conversationService.addMessage(report.getSubTaskId(), report.getAgentId(),
                        "assistant", "agent",
                        report.getOutput() != null ? report.getOutput() : "",
                        "sub_task_execute");
            } else {
                conversationService.addMessage(report.getSubTaskId(), report.getAgentId(),
                        "assistant", "agent",
                        report.getError() != null ? report.getError() : "unknown_error",
                        "sub_task_execute_failed");
            }
        } catch (Exception e) {
            log.warn("执行对话流写入失败（不阻断主链路）: subTaskId={}, err={}",
                    report.getSubTaskId(), e.getMessage());
        }

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
            // V27 核验门控：事务提交后异步触发 LLM 自动核验（AFTER_COMMIT 监听），
            // 核验 LLM 调用不阻塞结果回报事务；是否启用由监听侧按配置判定
            applicationEventPublisher.publishEvent(
                    new SubTaskSubmittedForReviewEvent(report.getSubTaskId(), report.getAgentId()));
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
        // 关键自死锁防护（v2.6 §4.1 2026-07-20 锁语义重审）：
        // v2.5 修复点：failureTracker 以 REQUIRES_NEW 独立事务更新同一 agent 行，
        // 而本事务在成功路径下已通过 subTaskService.submit() -> changeStatus(REVIEW)
        // -> heartbeatService.active() 锁定了该 agent 行；若在事务内直接调用，
        // 会形成"外层持锁 + 内层新事务改同一行"的自死锁。
        // 修复：把 failureTracker.recordSuccess/Failure 挪到主事务提交后（afterCommit）
        // 执行——此时行锁已释放，REQUIRES_NEW 独立语义仍保留。
        //
        // v2.6 补充（active() 现在复用 seen()，DB 行锁变频繁）：
        // seen() 内部会调 agentMapper.updateById(agent)，同样锁定 agent 行；
        // 若 failureTracker 留在主事务内，即便 active() 用 SELECT+UPDATE
        // 顺序，REQUIRES_NEW 内层仍会撞主层持有同一行的锁，因此 afterCommit
        // 模式不可豁免——本段逻辑必须保留。
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
     *
     * <p><b>v2.6 §4.1 2026-07-20 重新声明不可豁免</b>：{@code HeartbeatService.active()}
     * 在 v2.6 改为复用 seen() 双写，内部走 {@code agentMapper.updateById(agent)}
     * 锁 agent 行；本方法若留在主事务内（而非 afterCommit），会与外层事务
     * 持有的 agent 行锁形成自死锁。本类 L178-192 处的 afterCommit 注册不可删除。</p>
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
