package com.helloai.core.agent.command;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.output.ExecutionOutputParser;
import com.helloai.core.agent.output.ParsedOutput;
import com.helloai.core.agent.port.SubTaskCommandPort;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.port.TaskRunningSpecPort;
import com.helloai.core.agent.quality.ExecutorDoneIssuesBackfiller;
import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.core.shared.event.SubTaskSubmittedForReviewEvent;
import com.helloai.core.agent.service.ExecutionArtifactService;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.observability.ExternalAgentFailureTracker;
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
import java.util.List;
import java.util.Map;
import com.helloai.core.agent.port.TaskTimelinePort;

/**
 * 执行结果处理器。
 *
 * <p>负责把执行成功/失败结果回写到子任务状态机与时间线，
 * 让消费侧编排（{@code LocalExecutionCommandConsumer}）更聚焦“执行本身”，
 * 后续也便于把结果处理独立挂接到 MQ/轮询消费端。</p>
 *
 * <p><b>跨域访问（2026-10-01 W10）</b>：本类曾直接 import {@code task.entity.SubTask} /
 * {@code task.service.SubTaskService} / {@code task.spec.ExecutionRecord(Parser)} /
 * {@code task.service.TaskRunningSpecService}，构成 CODE_STYLE §6 反向依赖
 * （{@code agent → task}）。现一律改经 {@code agent.port} 端口：
 * 读走 {@link SubTaskQueryPort}（返回 {@link SubTaskSnapshot} 快照，非实体）、
 * 写走 {@link SubTaskCommandPort}（{@code submit / block / updateContext} 原子命令）、
 * 执行记录回填走 {@link TaskRunningSpecPort}（解析 + fallback 整体落 task 域）。
 * 端口归属判据见 §7.2：消费方 agent <b>低于</b>提供方 task ⇒ 契约落消费方、适配器落提供方。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionResultHandler {

    /** 子任务只读快照端口（原 {@code SubTaskService#getById}）。 */
    private final SubTaskQueryPort subTaskQueryPort;
    /** 子任务命令端口（原 {@code SubTaskService#submit/block/updateById}）。 */
    private final SubTaskCommandPort subTaskCommandPort;
    private final TaskTimelinePort taskTimelinePort;
    private final ExternalAgentFailureTracker failureTracker;
    private final AgentService agentService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ConversationService conversationService;
    private final ExecutionArtifactService executionArtifactService;
    /** 执行记录端口（原 {@code TaskRunningSpecService}；含 EXECUTION_RECORD 解析与 fallback）。 */
    private final TaskRunningSpecPort taskRunningSpecPort;
    private final ExecutionOutputParser executionOutputParser;
    private final ExecutorDoneIssuesBackfiller executorDoneIssuesBackfiller;
    /** Phase 0 B2：事件记录器（AGENT_COMPLETED 埋点；事件 write-only，失败仅告警不阻断回写）。 */
    private final AgentEventRecorder agentEventRecorder;
    /** Phase 1 Step 3：执行会话服务（终态 COMPLETED/FAILED；best-effort 不阻断回写）。 */
    private final AgentSessionService agentSessionService;
    /** 执行记录服务（804 归属校验：失败回写前确认本消费仍持有该 RUNNING 记录）。 */
    private final AgentExecutionRecordService agentExecutionRecordService;

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
        report.setThinking(result.getThinking());
        report.setError(null);
        handleReport(report);
    }

    /**
     * 失败回写（无归属信息；保留既有语义 —— 状态为 {@code IN_PROGRESS} 即推进 {@code BLOCKED}）。
     *
     * <p>补偿任务（超时判定后主动报失败）等调用方使用；执行记录已由调用方终结，故不做归属校验。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleFailure(Long subTaskId, Long agentId, Exception e) {
        applyFailure(subTaskId, agentId, null, null, e);
    }

    /**
     * 失败回写（含<b>归属校验</b>）。
     *
     * <p><b>2026-10-05 修 804 双消费假阻塞</b>：MQ 主路径与 DB Poller 兜底路径可能同时消费同一
     * 执行命令（同一 {@code recordId}）。输家在 {@code startIfNeeded} 的
     * {@code ASSIGNED→IN_PROGRESS} 乐观锁 CAS 上抛「并发修改，请重试」后进本入口——
     * 若不加校验会直接把<b>已被赢家接管</b>的子任务误打成 {@code BLOCKED}（产出被丢弃）。</p>
     *
     * <p>校验口径：仅当 {@code recordId} 对应记录仍为 {@code RUNNING}（即本轮上报方确实持有
     * 这次执行）时才推进 {@code BLOCKED}；记录非 {@code RUNNING}（他路已终结 / 从未启动）则
     * 判定为「并发修改 / 重复消费」的输家 —— 不 block，只落
     * {@code sub_task_report_blocked_skipped} 时间线，终态由接管方决定。</p>
     *
     * @param recordId 上报方持有的执行记录 ID；{@code null} 时退化为无归属校验（等同三参重载）
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleFailure(Long subTaskId, Long agentId, Long recordId, Exception e) {
        applyFailure(subTaskId, agentId, recordId, null, e);
    }

    /**
     * 失败回写（携带 thinking；归属校验同三参重载 —— {@code recordId} 恒 {@code null}）。
     *
     * <p><b>2026-10-05 方案 B 配套</b>：空产出被 A 判为 {@code FAILED} 后，模型思维链
     * （DeepSeek {@code reasoningContent}）仍需可见。本入口与三参重载同链（同 {@code handleReport}），
     * 仅额外透传 {@code thinking}，由 {@code handleReport} 落 conversation_message
     * （toolName={@code sub_task_execute_thinking}，**同一通道**）——<b>不改变失败语义</b>（仍 block）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleFailure(Long subTaskId, Long agentId, String thinking, Exception e) {
        applyFailure(subTaskId, agentId, null, thinking, e);
    }

    /**
     * 失败上报装配（private 核心）：三/四参重载与 thinking 重载统一收敛到此，避免构造重复。
     */
    private void applyFailure(Long subTaskId, Long agentId, Long recordId, String thinking, Exception e) {
        ExecutionResultReport report = new ExecutionResultReport();
        report.setSubTaskId(subTaskId);
        report.setAgentId(agentId);
        report.setRecordId(recordId);
        report.setSource("INTERNAL");
        report.setIdempotencyKey(null);
        report.setSuccess(false);
        report.setExecutorName(null);
        report.setFinishReason(null);
        report.setTokenUsage(null);
        report.setOutput(null);
        report.setThinking(thinking);
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

        SubTaskSnapshot subTask = subTaskQueryPort.findById(report.getSubTaskId());
        if (subTask == null) {
            ExecutionResultApplyResult r = new ExecutionResultApplyResult();
            r.setApplied(false);
            r.setStatus("subtask_not_found");
            return r;
        }

        Map<String, Object> ctx = new HashMap<>(subTask.context() != null ? subTask.context() : Map.of());
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

        if (subTask.status() != SubTaskStatus.IN_PROGRESS) {
            taskTimelinePort.recordEvent(
                    subTask.taskId(),
                    report.getSubTaskId(),
                    "sub_task_execute_result_discarded",
                    AgentRole.EXECUTOR,
                    report.getAgentId(),
                    safeMap(
                            "reason", "subtask_status_not_in_progress",
                            "currentStatus", subTask.status().name(),
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
        // 方案3 displayText：物化开启时 output/对话流写摘要+文件概览+尾部（EXECUTION_RECORD 保留），
        // 避免 manifest JSON 全文刷屏；物化关闭/降级时保持原文，与现状一致
        ParsedOutput parsedOutput = executionOutputParser.parse(subTask.title(), report.getOutput());
        String outputText = report.getOutput();
        if (!parsedOutput.isEmpty() && parsedOutput.displayText() != null
                && executionArtifactService.isEnabled()) {
            outputText = parsedOutput.displayText();
        }
        last.put("output", outputText);
        last.put("error", report.getError());
        ctx.put("lastExecution", last);

        // Task Running Spec 回填：从 executor 输出解析 EXECUTION_RECORD 块。
        // 解析 / fallback（前 200 字符）/ 落库整体在 task 域（TaskRunningSpecPort）完成——
        // ExecutionRecord(Parser) 是 task 协议类型，留在消费方会重新引入反向依赖。
        try {
            taskRunningSpecPort.parseAndAppendExecutionRecord(
                    subTask.taskId(), subTask.id(), subTask.title(), report.getAgentId(), report.getOutput());
        } catch (Exception e) {
            log.warn("Task Running Spec 回填失败（不阻断主链路）: subTaskId={}, err={}",
                    subTask.id(), e.getMessage());
        }

        // 读改写回写整体收为不透明命令：消费方只给目标 context，提供方自取最新行整体覆写
        subTaskCommandPort.updateContext(subTask.id(), ctx);

        // 对话流增量副本：执行产出/失败原因写入 conversation_message，
        // INTERNAL/EXTERNAL 上报共用本入口；REQUIRES_NEW 独立事务 + try/catch，失败不阻断主链路
        try {
            // B（2026-10-05）：思考过程与成功/失败无关 —— 只要模型产出了思考过程就落库
            // （同通道 sub_task_execute_thinking）。空产出被 A 判 FAILED 时，思维链仍应可见，
            // 供前端「看到模型到底思考了什么」；仅可观测，不参与成功/失败判定。
            if (report.getThinking() != null && !report.getThinking().isBlank()) {
                conversationService.addMessage(report.getSubTaskId(), report.getAgentId(),
                        "assistant", "agent",
                        report.getThinking(),
                        "sub_task_execute_thinking");
            }
            if (report.isSuccess()) {
                conversationService.addMessage(report.getSubTaskId(), report.getAgentId(),
                        "assistant", "agent",
                        outputText,
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
            subTaskCommandPort.submit(report.getSubTaskId());
            // Phase 1 Step 3：执行会话终态 COMPLETED（best-effort 不阻断回写）
            agentSessionService.complete(report.getSubTaskId(), report.getAgentId(),
                    AgentEventContextResolver.resolveTurn(subTask.reworkCount(), subTask.attemptTotal()));
            taskTimelinePort.recordEvent(
                    subTask.taskId(),
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
            // Phase 0 B2：AGENT_COMPLETED（Turn 端点事件 step=0；失败路径不发，ADR §5.3）
            try {
                agentEventRecorder.record(
                        AgentEventContextResolver.resolveRunId(subTask.taskId()),
                        subTask.taskId(), report.getSubTaskId(),
                        AgentEventContextResolver.resolveTurn(subTask.reworkCount(), subTask.attemptTotal()), 0,
                        AgentEventType.AGENT_COMPLETED, report.getAgentId(),
                        safeMap("success", report.isSuccess(),
                                "source", report.getSource(),
                                "executor", report.getExecutorName(),
                                "finishReason", report.getFinishReason(),
                                "tokens", report.getTokenUsage()));
            } catch (Exception e) {
                log.warn("Agent 事件记录失败（事件 write-only，降级不阻断主链路）: type={}, subTaskId={}, err={}",
                        AgentEventType.AGENT_COMPLETED, report.getSubTaskId(), e.getMessage());
            }
            // 核验门控：事务提交后异步触发 LLM 自动核验（AFTER_COMMIT 监听），
            // 核验 LLM 调用不阻塞结果回报事务；是否启用由监听侧按配置判定
            applicationEventPublisher.publishEvent(
                    new SubTaskSubmittedForReviewEvent(report.getSubTaskId(), report.getAgentId()));
            // 方案2 产出物化：仿 failureTracker 的 afterCommit 范式挂主事务提交后执行——
            // 物化内部会调 attachmentService.register（独立事务）与本地磁盘 IO，
            // 留在主事务内既拉长事务又有锁风险；best-effort，失败不影响 REVIEW 推进
            final Long materializeSubTaskId = report.getSubTaskId();
            final Long materializeAgentId = report.getAgentId();
            final ParsedOutput materializeParsed = parsedOutput;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        executionArtifactService.materialize(materializeSubTaskId, materializeAgentId, materializeParsed);
                    }
                });
            } else {
                executionArtifactService.materialize(materializeSubTaskId, materializeAgentId, materializeParsed);
            }

            // 反馈回路第 1 层：executorDoneIssues LLM 语义对比回填（异步 best-effort）。
            // 轻量预检（reviewHistory 最后一轮有空 executorDoneIssues 且有 issues）通过才
            // 注册 afterCommit 触发；完整校验与防覆盖在 ExecutorDoneIssuesBackfiller 内完成。
            if (report.getOutput() != null && !report.getOutput().isBlank()
                    && hasUnresolvedLastRound(ctx)) {
                final Long backfillSubTaskId = report.getSubTaskId();
                final String backfillOutput = report.getOutput();
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            executorDoneIssuesBackfiller.backfill(backfillSubTaskId, backfillOutput);
                        }
                    });
                } else {
                    executorDoneIssuesBackfiller.backfill(backfillSubTaskId, backfillOutput);
                }
            }
        } else {
            // 归属校验（2026-10-05 修 804 假 BLOCKED）：仅当本消费确实持有该 sub_task 的
            // RUNNING 执行记录时才推进 BLOCKED。否则（记录非 RUNNING，说明本轮是双消费的
            // 输家：他路已接管/已终结，或该记录从未由本轮启动）不 block，只留痕，
            // 避免把赢家正在执行的子任务误判为失败。
            if (report.getRecordId() != null && !isRecordRunning(report.getRecordId())) {
                taskTimelinePort.recordEvent(
                        subTask.taskId(),
                        report.getSubTaskId(),
                        "sub_task_report_blocked_skipped",
                        AgentRole.EXECUTOR,
                        report.getAgentId(),
                        safeMap(
                                "reason", "record_not_running_owned_by_other",
                                "recordId", report.getRecordId(),
                                "error", report.getError(),
                                "source", report.getSource()));
                log.warn("失败回写跳过 BLOCKED（记录非本轮持有, 判为双消费输家）: subTaskId={}, recordId={}, err={}",
                        report.getSubTaskId(), report.getRecordId(), report.getError());
            } else {
                // block(subTaskId, null, null) 逐字等价于 SubTaskService#block(Long)：
                // 仅写 blockedAt + 落 sub_task_report_blocked 时间线（reason 记空串）
                subTaskCommandPort.block(report.getSubTaskId(), null, null);
            }
            // Phase 1 Step 3：执行会话终态 FAILED（error 摘要；best-effort 不阻断回写）
            agentSessionService.fail(report.getSubTaskId(), report.getAgentId(),
                    AgentEventContextResolver.resolveTurn(subTask.reworkCount(), subTask.attemptTotal()), report.getError());
            taskTimelinePort.recordEvent(
                    subTask.taskId(),
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
        // 关键自死锁防护（§4.1  锁语义重审）：
        // 修复点：failureTracker 以 REQUIRES_NEW 独立事务更新同一 agent 行，
        // 而本事务在成功路径下已通过 subTaskCommandPort.submit() -> changeStatus(REVIEW)
        // -> heartbeatService.active() 锁定了该 agent 行；若在事务内直接调用，
        // 会形成"外层持锁 + 内层新事务改同一行"的自死锁。
        // 修复：把 failureTracker.recordSuccess/Failure 挪到主事务提交后（afterCommit）
        // 执行——此时行锁已释放，REQUIRES_NEW 独立语义仍保留。
        //
        // 补充（active() 现在复用 seen()，DB 行锁变频繁）：
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
     * 归属校验辅助：该执行记录当前是否为 {@code RUNNING}（即本轮上报方仍持有这次执行）。
     *
     * <p>见 {@link #handleFailure(Long, Long, Long, Exception)}；任何异常都降级为
     * {@code false}（宁可不 block，也不误阻塞赢家）。</p>
     */
    private boolean isRecordRunning(Long recordId) {
        try {
            AgentExecutionRecord record = agentExecutionRecordService.getById(recordId);
            return record != null && record.getStatus() == ExecutionStatus.RUNNING;
        } catch (Exception e) {
            log.warn("执行记录归属校验异常（按未持有处理，不 block）: recordId={}, err={}",
                    recordId, e.getMessage());
            return false;
        }
    }

    /**
     * executorDoneIssues 回填预检（轻量，仅看结构不落库）：
     * reviewHistory 最后一轮 executorDoneIssues 为空且 issues 非空 → 需要语义对比。
     * 完整校验（round 比对 / 防覆盖）由 {@link ExecutorDoneIssuesBackfiller} 在异步链路完成。
     */
    private boolean hasUnresolvedLastRound(Map<String, Object> ctx) {
        if (ctx == null) {
            return false;
        }
        Object historyObj = ctx.get("reviewHistory");
        if (!(historyObj instanceof List<?> history) || history.isEmpty()) {
            return false;
        }
        Object last = history.get(history.size() - 1);
        if (!(last instanceof Map<?, ?> m)) {
            return false;
        }
        Object done = m.get("executorDoneIssues");
        if (done instanceof List<?> doneList && !doneList.isEmpty()) {
            return false;
        }
        Object issues = m.get("issues");
        return (issues instanceof List<?> issueList && !issueList.isEmpty())
                || (issues instanceof String issueStr && !issueStr.isBlank());
    }

    /**
     * N11 计数写入（成功重置 / 失败累加）。由主事务 afterCommit 回调触发，
     * 确保执行时 agent 行锁已释放，避免与主链路事务自死锁。
     *
     * <p><b>§4.1  重新声明不可豁免</b>：{@code HeartbeatService.active()}
     * 在 改为复用 seen() 双写，内部走 {@code agentMapper.updateById(agent)}
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
