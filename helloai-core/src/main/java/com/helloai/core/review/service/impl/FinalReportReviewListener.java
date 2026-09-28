package com.helloai.core.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.review.picker.ReviewerPicker;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.review.support.FinalReportFidelityChecker;
import com.helloai.core.review.support.ReviewEvidenceAssembler;
import com.helloai.core.review.support.VerdictParser;
import com.helloai.core.shared.event.TaskFinalReportGeneratedEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskFinalReportService;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.spec.ExecutionRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 最终整合报告质量审查监听器（3A 核验/返工闭环）。
 *
 * <p>承接 {@link TaskFinalReportGeneratedEvent}：<b>先跑确定性机械前置门</b>
 * （{@link FinalReportFidelityChecker}：空壳引用 / 围栏失衡命中即机械驳回，不给 LLM 放过的机会）→
 * 选 Reviewer（复用 {@link ReviewerPicker}，报告无子任务实体——传仅填 taskId 的探针，
 * 任务级 {@code reviewerAgentId} 同样生效）→ 渲染质量审查 Prompt（验收标准镜像 3B 条款，
 * 输出 VerdictParser 可解析 JSON）→ 判定：</p>
 * <ul>
 *   <li><b>机械硬违规</b>：落 {@code task_final_report_review_rejected}（source=mechanical）并回调返工，
 *       不消耗审查 LLM 调用；</li>
 *   <li><b>机械软违规</b>（跨章逐字重复 / 覆盖追溯表缺失）：落 {@code task_final_report_review_warned}
 *       告警事件，交审查 LLM 重点关注（不直接驳回）；</li>
 *   <li><b>pass=true</b>：落 {@code task_final_report_review_passed}，闭环结束；</li>
 *   <li><b>pass=false 且未达返工上限</b>：落 rejected 事件并回调 {@link TaskFinalReportService#rework}
 *        （驳回意见注入重写 prompt 逐条响应）；</li>
 *   <li><b>pass=false 且已达上限</b>：落 {@code task_final_report_max_review_reached}，
 *        保留当前报告等人工/手动重生成；</li>
 *   <li><b>自审自过硬守卫</b>：reviewer 与写作者同 Agent 或选不到 reviewer 时，
 *        落 {@code task_final_report_review_skipped}（不得静默判 pass）。</li>
 * </ul>
 *
 * <p>审查失败/异常仅影响质量闭环，不影响任务 DONE 与报告已落库事实（与报告生成同哲学）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinalReportReviewListener {

    private static final String REVIEW_TEMPLATE_PATH = "prompts/task-final-report-review.md";
    /** 审查证据注入的子任务数量上限（防上下文爆炸；超出仅注入前 N 个，其余以清单为准）。 */
    private static final int MAX_EVIDENCE_SUB_TASKS = 10;

    private final TaskFinalReportService taskFinalReportService;
    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final TaskRunningSpecService taskRunningSpecService;
    private final ReviewerPicker reviewerPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final VerdictParser verdictParser;
    private final ReviewEvidenceAssembler reviewEvidenceAssembler;
    private final TaskTimelineService taskTimelineService;
    private final AgentDispatchProperties dispatchProperties;
    /** §12.2 审查专用池（helloai-start {@code ReportReviewExecutorConfig}）：审查提交后发布线程立即返回。 */
    @Qualifier("reportReviewExecutor")
    private final Executor reportReviewExecutor;

    /**
     * 同步入口：只做<b>提交</b>不阻塞发布线程。<p>
     *
     * <p>§12.2 审查异步化：审查体（选人 + LLM 判定 + 返工）提交到专用池执行。不用 {@code @Async}——
     * 其拒绝异常走 {@code AsyncUncaughtExceptionHandler} 不会回到发布线程，无法落
     * {@code review_skipped(executor_saturated)}；手动 {@code execute} + try-catch
     * {@link RejectedExecutionException} 才能在发布线程兜底。</p>
     */
    @EventListener
    public void onFinalReportGenerated(TaskFinalReportGeneratedEvent event) {
        if (!dispatchProperties.isAutoFinalReportReviewEnabled()) {
            log.debug("最终报告自动审查未启用，跳过: taskId={}, attempt={}", event.getTaskId(), event.getAttempt());
            // 收敛兜底：开关若在生成后、审查前被关闭，把可能残留的 REVIEWING 收敛 DONE（条件写回，版本未变才生效）
            convergeToDone(event);
            return;
        }
        try {
            reportReviewExecutor.execute(() -> reviewQuietly(event));
        } catch (RejectedExecutionException e) {
            log.warn("最终报告审查线程池已满，本次审查跳过: taskId={}, attempt={}", event.getTaskId(), event.getAttempt());
            skipped(event, "executor_saturated");
        }
    }

    /** 异步审查体：异常吞掉（审查失败/异常不影响报告交付，与报告生成同哲学）。 */
    private void reviewQuietly(TaskFinalReportGeneratedEvent event) {
        try {
            review(event);
        } catch (Exception e) {
            log.warn("最终报告审查异常（不影响报告交付）: taskId={}, err={}", event.getTaskId(), e.getMessage());
        }
    }

    /**
     * §12.2 REVIEWING 收敛：条件写回 DONE（{@code final_report_time} 未被替换才生效）。
     * 影响行数 0 = 版本已被新生成/回滚接管，本链放弃收敛，不覆盖新链状态（陈旧守卫第三条防线）。
     */
    private void convergeToDone(TaskFinalReportGeneratedEvent event) {
        // 注：此处条件等值由 PG 侧按 timestamptz 语义判定，**天然是瞬间比较**（不由 JVM 侧偏移决定），
        // 故与 isStale 的 isEqual 口径一致，无需改造；请勿"对称地"改回 Java 侧 equals 比较
        boolean updated = taskService.update(new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, event.getTaskId())
                .eq(Task::getFinalReportTime, event.getReportTime())
                .set(Task::getFinalReportStatus, FinalReportStatus.DONE));
        if (!updated) {
            log.debug("最终报告审查收敛放弃（版本已更新，新链接管状态）: taskId={}", event.getTaskId());
        }
    }

    /**
     * §12.2 陈旧守卫：库中 {@code final_report_time} 与事件锚点不一致 = 版本已被更新/回滚，旧链失效。
     *
     * <p><b>必须按「同一瞬间」比较</b>：pgjdbc 读 {@code timestamptz} 返回 UTC 偏移
     * （{@code ...Z}），而事件锚点是写入时的本地偏移（{@code ...+08:00}）——两者是同一瞬间。
     * {@link OffsetDateTime#equals(Object)} 连偏移一起比，会**恒不相等**，把每次生成都误判为陈旧
     * （实测：审查被丢弃且不收敛 → 报告永久卡在 {@code REVIEWING}，需人工介入）；
     * 故用 {@link OffsetDateTime#isEqual(OffsetDateTime)}（只比瞬间，与时区无关）。</p>
     */
    private static boolean isStale(Task task, TaskFinalReportGeneratedEvent event) {
        OffsetDateTime dbTime = task.getFinalReportTime();
        OffsetDateTime eventTime = event.getReportTime();
        if (dbTime == null || eventTime == null) {
            return true;
        }
        return !dbTime.isEqual(eventTime);
    }

    private void review(TaskFinalReportGeneratedEvent event) {
        Task task = taskService.getById(event.getTaskId());
        if (task == null || task.getFinalReport() == null || task.getFinalReport().isBlank()) {
            log.debug("报告不存在或为空，跳过审查: taskId={}", event.getTaskId());
            return;
        }
        // §12.2 陈旧守卫（审查前）：期间被重新生成/回滚接管 → 旧链审查丢弃，不选人不动用 LLM
        if (isStale(task, event)) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_discarded_stale", AgentRole.REVIEWER, null,
                    Map.of("attempt", event.getAttempt()));
            log.debug("最终报告审查丢弃（版本已更新，旧链失效）: taskId={}, attempt={}",
                    event.getTaskId(), event.getAttempt());
            return;
        }
        // 3A-V2 机械前置门（确定性、零 token）：空壳引用 / 围栏失衡命中即机械驳回，
        // 不给 LLM 放过的机会（实测「完整矩阵见分册」曾被 LLM 判 pass）
        FinalReportFidelityChecker.Result fidelity = FinalReportFidelityChecker.check(task.getFinalReport());
        if (fidelity.hasHard()) {
            log.info("最终报告机械校验命中硬违规，直接驳回: taskId={}, issues={}",
                    event.getTaskId(), fidelity.hardIssueList());
            rejectOrRework(event, null, 0, fidelity.hardIssueList(), "mechanical");
            return;
        }
        Agent reviewer = pickReviewer(event.getTaskId());
        if (reviewer == null) {
            skipped(event, "no_reviewer_available");
            return;
        }
        // 自审自过硬守卫：审查人不得是报告写作者（不满足则跳过审查，不得静默判 pass）
        if (task.getFinalReportAgentId() != null
                && reviewer.getId().equals(task.getFinalReportAgentId())) {
            skipped(event, "self_review_guard");
            return;
        }
        // 机械软违规（跨章逐字重复 / 覆盖追溯表缺失）：落告警事件，交审查 LLM 重点关注（不直接驳回）
        if (fidelity.hasSoft()) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_warned", AgentRole.REVIEWER, reviewer.getId(),
                    Map.of("softIssues", fidelity.softIssueList(), "attempt", event.getAttempt()));
            log.info("最终报告机械校验命中软违规（交 LLM 复核）: taskId={}, soft={}",
                    event.getTaskId(), fidelity.softIssueList());
        }
        AgentResult result = callReviewLlm(event, task, reviewer);
        if (result == null) {
            // §12.2 REVIEWING 收敛：审查 LLM 调用失败/异常仅记 failed 事件（callReviewLlm 内部），
            // 报告照常交付——不收敛则 REVIEWING 永远卡住无恢复口
            convergeToDone(event);
            return;
        }
        SubTaskReviewService.ReviewVerdict verdict = verdictParser.parseVerdict(result.getOutput());
        if (verdict == null) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_unparseable", AgentRole.REVIEWER, reviewer.getId(),
                    Map.of("rawOutput", VerdictParser.summarize(result.getOutput(), 300),
                            "attempt", event.getAttempt()));
            log.warn("最终报告审查输出不可解析: taskId={}, raw={}", event.getTaskId(),
                    VerdictParser.summarize(result.getOutput(), 300));
            // §12.2 REVIEWING 收敛：审查无法判定仅记事件，报告照常交付
            convergeToDone(event);
            return;
        }
        int score = verdict.getScore() != null ? verdict.getScore() : 0;
        if (Boolean.TRUE.equals(verdict.getPass())) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_passed", AgentRole.REVIEWER, reviewer.getId(),
                    Map.of("reviewerAgentId", reviewer.getId(), "score", score,
                            "attempt", event.getAttempt()));
            log.info("最终报告审查通过: taskId={}, reviewerAgentId={}, score={}",
                    event.getTaskId(), reviewer.getId(), score);
            // §12.2 REVIEWING 收敛：审查通过置 DONE（条件写回，版本未变才生效）
            convergeToDone(event);
            return;
        }
        rejectOrRework(event, reviewer.getId(), score,
                verdict.getIssues() != null ? verdict.getIssues() : "（无具体驳回意见）", "llm");
    }

    /**
     * 驳回统一处置：落 {@code task_final_report_review_rejected} 事件 → 未达返工上限回调
     * {@link TaskFinalReportService#rework}；达上限落 {@code task_final_report_max_review_reached}
     * 保留当前报告等人工（不重写）。
     *
     * @param reviewerAgentId 审查人（机械驳回时为 null）
     * @param source          驳回来源：{@code mechanical}（确定性校验）/ {@code llm}（审查模型）
     */
    private void rejectOrRework(TaskFinalReportGeneratedEvent event, Long reviewerAgentId,
                                int score, String issues, String source) {
        int maxReview = dispatchProperties.getAutoFinalReportMaxReview();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reviewerAgentId", reviewerAgentId);
        payload.put("score", score);
        payload.put("attempt", event.getAttempt());
        payload.put("issues", issues != null ? issues : "");
        payload.put("source", source);
        taskTimelineService.recordEvent(event.getTaskId(), null,
                "task_final_report_review_rejected", AgentRole.REVIEWER, reviewerAgentId, payload);
        if (maxReview > 0 && event.getAttempt() <= maxReview) {
            // §12.2 陈旧守卫（rework 前）：审查耗时期间版本可能已被新生成/回滚接管——
            // 此时返工基于旧链已无意义，丢弃并落 rework_discarded_stale（不重写不收敛）
            Task latest = taskService.getById(event.getTaskId());
            if (latest == null || isStale(latest, event)) {
                taskTimelineService.recordEvent(event.getTaskId(), null,
                        "task_final_report_rework_discarded_stale", AgentRole.REVIEWER,
                        reviewerAgentId, Map.of("attempt", event.getAttempt()));
                log.debug("最终报告返工丢弃（版本已更新，旧链失效）: taskId={}, attempt={}",
                        event.getTaskId(), event.getAttempt());
                return;
            }
            log.info("最终报告审查驳回（{}），触发返工重写: taskId={}, attempt={}, maxReview={}",
                    source, event.getTaskId(), event.getAttempt(), maxReview);
            // 去状态化（§12.3）：同轮返工显式传 attempt + 1，轮次只随调用链传递、无任何存储
            taskFinalReportService.rework(event.getTaskId(),
                    issues != null ? issues : "（无具体驳回意见）", event.getAttempt() + 1);
            return;
        }
        taskTimelineService.recordEvent(event.getTaskId(), null,
                "task_final_report_max_review_reached", AgentRole.REVIEWER, reviewerAgentId,
                Map.of("attempt", event.getAttempt(), "maxReview", maxReview));
        log.info("最终报告驳回已达返工上限，保留当前报告等人工: taskId={}, attempt={}, maxReview={}",
                event.getTaskId(), event.getAttempt(), maxReview);
        // §12.2 REVIEWING 收敛：达上限不再重写，报告保留现状并收敛 DONE（不再等待）
        convergeToDone(event);
    }

    /** 渲染审查 Prompt 并调用平台 LLM；调用失败/异常返回 null（内部已记 failed 事件）。 */
    private AgentResult callReviewLlm(TaskFinalReportGeneratedEvent event, Task task, Agent reviewer) {
        String prompt;
        try {
            prompt = renderReviewPrompt(task, event);
        } catch (Exception e) {
            log.warn("最终报告审查 Prompt 渲染失败: taskId={}, err={}", event.getTaskId(), e.getMessage());
            return null;
        }
        try {
            AgentTask agentTask = AgentTask.builder()
                    .systemPrompt("")
                    .userPrompt(prompt)
                    .context(Map.of("taskId", event.getTaskId(), "scene", "task_final_report_review"))
                    .requiredCapabilities(Map.of())
                    .build();
            AgentResult result = platformAgentExecutionService.executeSync(reviewer, agentTask);
            if (result == null || !result.isSuccess()) {
                taskTimelineService.recordEvent(event.getTaskId(), null,
                        "task_final_report_review_failed", AgentRole.REVIEWER, reviewer.getId(),
                        Map.of("error", result != null ? result.getErrorMessage() : "null_result",
                                "attempt", event.getAttempt()));
                log.warn("最终报告审查 LLM 调用失败: taskId={}, err={}",
                        event.getTaskId(), result != null ? result.getErrorMessage() : "null_result");
                return null;
            }
            return result;
        } catch (Exception e) {
            taskTimelineService.recordEvent(event.getTaskId(), null,
                    "task_final_report_review_failed", AgentRole.REVIEWER, reviewer.getId(),
                    Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                            "attempt", event.getAttempt()));
            log.warn("最终报告审查 LLM 调用异常: taskId={}, err={}", event.getTaskId(), e.getMessage());
            return null;
        }
    }

    /** 报告无子任务实体：传探针（仅填 taskId）使任务级 {@code reviewerAgentId} 生效（SubTask 仅 @Data 无 @Builder）。 */
    private Agent pickReviewer(Long taskId) {
        SubTask probe = new SubTask();
        probe.setTaskId(taskId);
        return reviewerPicker.pickSingle(probe);
    }

    /** 跳过审查落库标记（reason 区分守卫/无可用 reviewer/池满），不做任何 pass 判定；审查跳过即收敛 DONE。 */
    private void skipped(TaskFinalReportGeneratedEvent event, String reason) {
        taskTimelineService.recordEvent(event.getTaskId(), null,
                "task_final_report_review_skipped", AgentRole.REVIEWER, null,
                Map.of("reason", reason, "attempt", event.getAttempt()));
        log.info("最终报告审查跳过（{}）: taskId={}, attempt={}", reason, event.getTaskId(), event.getAttempt());
        // §12.2 REVIEWING 收敛：审查跳过（无审查人/自审守卫/池满）即收敛 DONE，报告照常交付
        convergeToDone(event);
    }

    /** 渲染审查 Prompt：任务信息 + 报告正文 + 子任务产出证据（产出摘要复用 review 域装配器同款口径）。 */
    private String renderReviewPrompt(Task task, TaskFinalReportGeneratedEvent event) {
        ClassPathResource resource = new ClassPathResource(REVIEW_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("未找到最终报告审查 Prompt 模板: " + REVIEW_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取最终报告审查 Prompt 模板失败: " + e.getMessage(), e);
        }
        return template
                .replace("{{TASK_TITLE}}", task.getTitle() != null ? task.getTitle() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.getDescription() != null && !task.getDescription().isBlank()
                                ? task.getDescription() : "（无补充描述）")
                .replace("{{FINAL_REPORT}}", task.getFinalReport() != null ? task.getFinalReport() : "")
                .replace("{{SUB_TASK_EVIDENCE}}", buildSubTaskEvidence(event.getTaskId()))
                .replace("{{ATTEMPT}}", String.valueOf(event.getAttempt()))
                .replace("{{REPORT_LENGTH}}", String.valueOf(event.getReportLength()));
    }

    /** 子任务产出证据：编号/标题/交付物/验收/执行摘要 + 产出摘要（与报告链同口径的只读事实源）。 */
    private String buildSubTaskEvidence(Long taskId) {
        List<SubTask> subTasks = subTaskService.lambdaQuery()
                .eq(SubTask::getTaskId, taskId)
                .orderByAsc(SubTask::getCreateTime)
                .list();
        List<SubTask> done = subTasks == null ? List.of() : subTasks.stream()
                .filter(st -> st.getStatus() == SubTaskStatus.DONE)
                .toList();
        if (done.isEmpty()) {
            return "（无 DONE 子任务产出证据）";
        }
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        for (int i = 0; i < done.size(); i++) {
            if (i >= MAX_EVIDENCE_SUB_TASKS) {
                truncated = true;
                break;
            }
            SubTask st = done.get(i);
            sb.append("### #").append(i + 1).append(' ')
                    .append(st.getTitle() != null ? st.getTitle() : "（无标题）").append('\n');
            if (st.getDeliverable() != null && !st.getDeliverable().isBlank()) {
                sb.append("- 交付物要求：").append(st.getDeliverable()).append('\n');
            }
            if (st.getAcceptance() != null && !st.getAcceptance().isBlank()) {
                sb.append("- 验收标准：").append(st.getAcceptance()).append('\n');
            }
            ExecutionRecord record = taskRunningSpecService.findRecord(taskId, st.getId());
            if (record != null && record.summary() != null && !record.summary().isBlank()) {
                sb.append("- 执行摘要：").append(record.summary()).append('\n');
            }
            sb.append("- 产出摘要：").append(reviewEvidenceAssembler.extractExecutionOutput(st)).append('\n');
        }
        if (truncated) {
            sb.append("\n（产出证据数量超上限，其余子任务仅以上述清单为准）\n");
        }
        return sb.toString().trim();
    }
}