package com.helloai.core.review;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.ReviewResult;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.command.ExecutionCommandService;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.execution.PlatformAgentExecutionService;
import com.helloai.core.agent.executor.AgentSelector;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.shared.event.SubTaskSubmittedForReviewEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子任务 LLM 自动核验服务（V27 内循环核验门控，与 PlannerAnalysisService 平级）。
 *
 * <p>入口 {@link #reviewSubTask(Long, Long)}：读取子任务 title/content/deliverable/acceptance
 * + 执行产出（context.lastExecution.output），构造核验 Prompt，经
 * {@link PlatformAgentExecutionService#executeSync} 调平台内 LLM 判定：</p>
 * <ul>
 *     <li>通过 → {@link SubTaskService#complete}（REVIEW→DONE，触发隐式评分与下游解锁）</li>
 *     <li>不通过 → {@link SubTaskService#rework}（REVIEW→REWORK，核验意见写入 context），
 *         并对 API_KEY_LLM 执行者重新下发执行命令闭合返工链</li>
 *     <li>LLM 调用失败/超时/输出不可解析 → <b>不改状态</b>，子任务停留 REVIEW 等人工兜底</li>
 * </ul>
 *
 * <p>触发点：{@code ExecutionResultHandler} 成功提交（→REVIEW）后发布
 * {@link SubTaskSubmittedForReviewEvent}，本类以 AFTER_COMMIT + @Async 异步消费，
 * 核验 LLM 调用不阻塞结果回报事务。</p>
 *
 * <p>防重：核验前检查当前状态仍为 REVIEW；reworkCount 达
 * {@code helloai.dispatch.auto-review-max-rework}（默认 3）后停留 REVIEW 等人工，
 * 不再自动打回，避免"执行→驳回→重执行"无限循环。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskReviewService {

    private static final String PROMPT_TEMPLATE_PATH = "prompts/subtask-review.md";
    private static final int OUTPUT_SUMMARY_LIMIT = 4000;

    private final SubTaskService subTaskService;
    private final AgentSelector agentSelector;
    private final AgentService agentService;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final ExecutionCommandService executionCommandService;
    private final AgentDispatchProperties dispatchProperties;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;
    private final ReviewService reviewService;

    /** AFTER_COMMIT 异步监听：结果回报事务提交后触发自动核验。 */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmittedForReview(SubTaskSubmittedForReviewEvent event) {
        if (!dispatchProperties.isAutoReviewEnabled()) {
            log.debug("自动核验未启用，子任务停留 REVIEW 等人工: subTaskId={}", event.getSubTaskId());
            return;
        }
        try {
            reviewSubTask(event.getSubTaskId(), event.getExecutorAgentId());
        } catch (Exception e) {
            log.warn("自动核验异常，子任务停留 REVIEW 等人工兜底: subTaskId={}, err={}",
                    event.getSubTaskId(), e.getMessage());
        }
    }

    /**
     * 对指定子任务执行一次 LLM 自动核验。
     *
     * <p>不加类级事务：LLM 调用耗时较长；complete/rework 各自内部事务原子提交，
     * 判定失败/不可解析时不改状态（子任务停留 REVIEW）。</p>
     */
    public void reviewSubTask(Long subTaskId, Long executorAgentId) {
        SubTask subTask = subTaskService.getById(subTaskId);
        if (subTask == null) {
            log.warn("自动核验跳过：子任务不存在, subTaskId={}", subTaskId);
            return;
        }
        // 防重：状态必须仍为 REVIEW（可能已被人工审查推进）
        if (subTask.getStatus() != SubTaskStatus.REVIEW) {
            log.debug("自动核验跳过：状态非 REVIEW, subTaskId={}, status={}", subTaskId, subTask.getStatus());
            return;
        }
        // 返工次数上限：达上限后停留 REVIEW 等人工，不再自动打回
        int reworkCount = subTask.getReworkCount() != null ? subTask.getReworkCount() : 0;
        int maxRework = dispatchProperties.getAutoReviewMaxRework();
        if (maxRework > 0 && reworkCount >= maxRework) {
            log.warn("自动核验跳过：返工已达上限, subTaskId={}, reworkCount={}, max={}",
                    subTaskId, reworkCount, maxRework);
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_auto_review_skip_max_rework", AgentRole.REVIEWER, null,
                    Map.of("reworkCount", reworkCount, "maxRework", maxRework));
            return;
        }

        Agent reviewer = pickReviewerAgent();
        if (reviewer == null) {
            log.warn("自动核验跳过：无可用平台内核验 Agent（REVIEWER/PLANNER 且 API_KEY_LLM），"
                    + "子任务停留 REVIEW 等人工: subTaskId={}", subTaskId);
            return;
        }

        String prompt = renderPrompt(subTask);
        AgentResult result;
        try {
            AgentTask agentTask = AgentTask.builder()
                    .subTaskId(subTaskId)
                    .systemPrompt("")
                    .userPrompt(prompt)
                    .context(Map.of("subTaskId", subTaskId, "scene", "subtask_review"))
                    .requiredCapabilities(Map.of())
                    .build();
            result = platformAgentExecutionService.executeSync(reviewer, agentTask);
        } catch (Exception e) {
            log.warn("自动核验 LLM 调用异常，子任务停留 REVIEW: subTaskId={}, err={}", subTaskId, e.getMessage());
            return;
        }
        if (result == null || !result.isSuccess()) {
            log.warn("自动核验 LLM 调用失败，子任务停留 REVIEW: subTaskId={}, err={}",
                    subTaskId, result != null ? result.getErrorMessage() : "null_result");
            return;
        }

        // 对话流双写：核验 Prompt + REVIEWER 分析原文全量落 conversation_message，
        // 不可解析时同样保留原始输出（正是人工兜底最需要看的内容）；失败不阻断核验主链路
        try {
            conversationService.addMessage(subTaskId, null,
                    "user", "platform", prompt, "subtask_review_prompt");
            // 推理模型的思考过程单独落一条消息（保留 thinking，供前端动态展示）
            if (result.getThinking() != null && !result.getThinking().isBlank()) {
                conversationService.addMessage(subTaskId, reviewer.getId(),
                        "assistant", "agent",
                        result.getThinking(),
                        "subtask_review_thinking");
            }
            conversationService.addMessage(subTaskId, reviewer.getId(),
                    "assistant", "agent",
                    result.getOutput() != null ? result.getOutput() : "",
                    "subtask_review_verdict");
        } catch (Exception e) {
            log.warn("核验对话流写入失败（不阻断核验）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }

        ReviewVerdict verdict = parseVerdict(result.getOutput());
        if (verdict == null) {
            log.warn("自动核验输出不可解析，子任务停留 REVIEW 等人工: subTaskId={}, rawOutput={}",
                    subTaskId, summarize(result.getOutput(), 300));
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_auto_review_unparseable", AgentRole.REVIEWER, reviewer.getId(),
                    Map.of("rawOutput", summarize(result.getOutput(), 300)));
            return;
        }

        if (Boolean.TRUE.equals(verdict.getPass())) {
            subTaskService.complete(subTaskId);
            recordAutoReviewQuietly(subTaskId, reviewer.getId(), ReviewResult.APPROVED, verdict);
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_auto_review_passed", AgentRole.REVIEWER, reviewer.getId(),
                    safeMap("score", verdict.getScore(), "comment", verdict.getComment()));
            log.info("自动核验通过: subTaskId={}, reviewerAgentId={}, score={}",
                    subTaskId, reviewer.getId(), verdict.getScore());
        } else {
            rejectAndRework(subTask, executorAgentId, reviewer.getId(), verdict);
        }
    }

    /** 驳回处理：核验意见写入 context，rework 累加 reworkCount，并对 API_KEY_LLM 执行者重派执行命令。 */
    private void rejectAndRework(SubTask subTask, Long executorAgentId, Long reviewerAgentId, ReviewVerdict verdict) {
        Long subTaskId = subTask.getId();
        Long targetExecutor = executorAgentId != null ? executorAgentId : subTask.getAssignedAgentId();

        // 核验意见写入子任务 context（作为返工原因，供执行者/人工查看）
        try {
            SubTask fresh = subTaskService.getById(subTaskId);
            if (fresh != null) {
                Map<String, Object> ctx = new HashMap<>(fresh.getContext() != null ? fresh.getContext() : Map.of());
                Map<String, Object> reviewInfo = new HashMap<>();
                reviewInfo.put("issues", verdict.getIssues());
                reviewInfo.put("comment", verdict.getComment());
                reviewInfo.put("score", verdict.getScore());
                reviewInfo.put("reviewerAgentId", reviewerAgentId);
                ctx.put("lastAutoReview", reviewInfo);
                fresh.setContext(ctx);
                subTaskService.updateById(fresh);
            }
        } catch (Exception e) {
            log.warn("核验意见写入 context 失败（不阻断返工）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }

        subTaskService.rework(subTaskId, targetExecutor);
        recordAutoReviewQuietly(subTaskId, reviewerAgentId, ReviewResult.REJECTED, verdict);
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                "sub_task_auto_review_rejected", AgentRole.REVIEWER, reviewerAgentId,
                safeMap("score", verdict.getScore(), "issues", verdict.getIssues(),
                        "comment", verdict.getComment()));
        log.info("自动核验驳回返工: subTaskId={}, reviewerAgentId={}, issues={}",
                subTaskId, reviewerAgentId, summarize(verdict.getIssues(), 200));

        // 内循环闭合：对 API_KEY_LLM 执行者重新下发执行命令，触发返工重执行
        if (targetExecutor == null) {
            return;
        }
        Agent executor = agentService.getById(targetExecutor);
        if (executor == null || executor.getAccessType() != AgentAccessType.API_KEY_LLM) {
            log.debug("返工不自动重执行（执行者非 API_KEY_LLM 或不存在），等外部/人工链路: subTaskId={}, executorAgentId={}",
                    subTaskId, targetExecutor);
            return;
        }
        try {
            executionCommandService.createAssignedCommand(subTaskId, targetExecutor, "auto-review-rework");
            log.info("返工重执行命令已下发: subTaskId={}, executorAgentId={}", subTaskId, targetExecutor);
        } catch (Exception e) {
            log.warn("返工重执行命令下发失败（子任务停留 REWORK 等兜底）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
        }
    }

    /** 自动核验落 review_record（仅记录；失败不阻断主链路）。score 缺失时按判定结果兜底并限幅 1~5。 */
    private void recordAutoReviewQuietly(Long subTaskId, Long reviewerAgentId,
                                          ReviewResult result, ReviewVerdict verdict) {
        try {
            int fallback = result == ReviewResult.APPROVED ? 3 : 1;
            int score = verdict.getScore() != null ? verdict.getScore() : fallback;
            score = Math.max(1, Math.min(5, score));
            reviewService.recordAutoReview(subTaskId, reviewerAgentId, result, score,
                    verdict.getIssues(), verdict.getComment());
        } catch (Exception e) {
            log.warn("自动核验落 review_record 失败（不阻断主链路）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
        }
    }

    /** 选平台内核验 Agent：优先 REVIEWER，回退 PLANNER；均要求 API_KEY_LLM，无则返回 null。 */
    private Agent pickReviewerAgent() {
        Agent preferred = agentSelector.pickPreferred(AgentRole.REVIEWER);
        if (preferred != null && preferred.getAccessType() == AgentAccessType.API_KEY_LLM) {
            return preferred;
        }
        Agent reviewer = firstApiKeyLlm(AgentRole.REVIEWER);
        if (reviewer != null) {
            return reviewer;
        }
        return firstApiKeyLlm(AgentRole.PLANNER);
    }

    private Agent firstApiKeyLlm(AgentRole role) {
        List<Agent> candidates = agentService.listByRole(role);
        if (candidates == null) {
            return null;
        }
        return candidates.stream()
                .filter(a -> a.getAccessType() == AgentAccessType.API_KEY_LLM)
                .findFirst()
                .orElse(null);
    }

    /** 加载核验 Prompt 模板并替换占位符。 */
    private String renderPrompt(SubTask subTask) {
        ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取核验 Prompt 模板失败: " + e.getMessage(), e);
        }
        return template
                .replace("{{SUB_TASK_TITLE}}", nullToEmpty(subTask.getTitle()))
                .replace("{{SUB_TASK_CONTENT}}", nullToEmpty(subTask.getContent()))
                .replace("{{DELIVERABLE}}", nullToEmpty(subTask.getDeliverable()))
                .replace("{{ACCEPTANCE}}", nullToEmpty(subTask.getAcceptance()))
                .replace("{{EXECUTION_OUTPUT}}", extractExecutionOutput(subTask));
    }

    /** 从 context.lastExecution.output 提取执行产出，缺失时给出占位说明。 */
    private String extractExecutionOutput(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx != null && ctx.get("lastExecution") instanceof Map<?, ?> lastExecution) {
            Object output = lastExecution.get("output");
            if (output != null && !output.toString().isBlank()) {
                return summarize(output.toString(), OUTPUT_SUMMARY_LIMIT);
            }
        }
        return "（执行产出为空或缺失，请据交付物/验收标准审慎判定）";
    }

    /** 解析核验判定 JSON；不可解析返回 null（调用方据此停留 REVIEW）。 */
    ReviewVerdict parseVerdict(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        String cleaned = stripToJsonObject(rawOutput);
        try {
            ReviewVerdict verdict = objectMapper.readValue(cleaned, ReviewVerdict.class);
            if (verdict == null || verdict.getPass() == null) {
                return null;
            }
            return verdict;
        } catch (Exception e) {
            return null;
        }
    }

    /** 剥离 markdown 代码块围栏，并兜底截取首尾花括号之间的 JSON 对象。 */
    private String stripToJsonObject(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) {
                cleaned = cleaned.substring(0, fenceEnd);
            }
            cleaned = cleaned.trim();
        }
        if (!cleaned.startsWith("{")) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }
        return cleaned;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String summarize(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "...";
    }

    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i] instanceof String key) {
                result.put(key, keyValues[i + 1]);
            }
        }
        return result;
    }

    /** LLM 核验判定结构化输出（未知字段容忍）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReviewVerdict {
        private Boolean pass;
        private Integer score;
        private String issues;
        private String comment;
        /** 逐条对照验收标准的核验分析过程（人工复核判定思路的材料，全文进对话流） */
        private String analysis;
    }
}
