package com.helloai.core.review.support;

import com.helloai.common.config.ReviewProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.port.UncertaintyView;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 单次核验执行引擎（§7.8 拆分：核验执行与编排分离，SubTaskReviewServiceImpl 迁出）。
 *
 * <p>职责边界：渲染核验 Prompt → 平台 LLM 调用 → 对话流双写 → 判定解析。
 * <b>不改状态不落库</b>：失败/不可解析返回 null（内部已记日志/timeline），
 * 由调用方决定停留 REVIEW 或按共识落地。</p>
 *
 * <p>单审（doReview）、双审（doDualReview）、抽检复审（ReviewRecheckExecutor）
 * 三方共用同一执行口径，保证判定语义一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewExecutionEngine {

    private static final String PROMPT_TEMPLATE_PATH = "prompts/subtask-review.md";

    /**
     * 单次核验结果（`RV-2`）：结构化判定 + 静态校验 findings。
     *
     * <p>{@link #inconclusive()} 为 true 表示存在 {@code BLOCK} 级 finding（当前唯一来源是
     * 「驳回却给满分」的逻辑矛盾）。调用方据此落 {@code INCONCLUSIVE}、停 REVIEW 转人工，
     * 不得再按 pass/score 推进状态。静态校验开关关闭时 findings 恒空 ⇒ 行为回到改造前。</p>
     */
    public record ExecutionOutcome(SubTaskReviewService.ReviewVerdict verdict,
                                   List<ReviewVerdictValidator.Finding> findings) {

        public ExecutionOutcome {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        public static ExecutionOutcome empty() {
            return new ExecutionOutcome(null, List.of());
        }

        /** 是否存在 BLOCK 级 finding（判定不可采信）。 */
        public boolean inconclusive() {
            return !blockFindings().isEmpty();
        }

        public List<ReviewVerdictValidator.Finding> blockFindings() {
            return findings.stream()
                    .filter(f -> f.severity() == ReviewVerdictValidator.Severity.BLOCK)
                    .toList();
        }
    }

    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final ConversationService conversationService;
    private final TaskTimelineService taskTimelineService;
    private final VerdictParser verdictParser;
    private final ReviewEvidenceAssembler reviewEvidenceAssembler;
    private final SubTaskService subTaskService;
    private final ReviewProperties reviewProperties;

    /**
     * 单次核验（不改状态不落库）：渲染 Prompt → LLM 调用 → 对话流双写 → 解析判定；
     * 失败/不可解析返回 null（内部已记日志/timeline，调用方据此停留 REVIEW 等人工）。
     *
     * <p>默认走单审链路（{@link ReviewChannel#SINGLE}），双审/抽检显式传链路来源，
     * 对话流消息类型随链路切换（subtask_review_* / subtask_dual_review_* /
     * subtask_recheck_*），保证执行对话流可分辨三种链路。</p>
     *
     * @param subTask  待核验子任务
     * @param reviewer 核验 Reviewer Agent
     * @return 结构化判定 + 静态校验 findings；LLM 调用失败/超时/输出不可解析时 verdict 为 null
     */
    public ExecutionOutcome execute(SubTaskView subTask, AgentProfileSnapshot reviewer) {
        return execute(subTask, reviewer, ReviewChannel.SINGLE);
    }

    /**
     * 单次核验（不改状态不落库）：同 {@link #execute(SubTaskView, AgentProfileSnapshot)}，按链路来源
     * 切换对话流消息类型前缀（单审/双审/抽检三态可分辨）。
     *
     * @param subTask  待核验子任务
     * @param reviewer 核验 Reviewer Agent
     * @param channel  链路来源（SINGLE / DUAL / RECHECK），决定消息类型前缀
     * @return 结构化判定 + 静态校验 findings；LLM 调用失败/超时/输出不可解析时 verdict 为 null
     */
    public ExecutionOutcome execute(SubTaskView subTask, AgentProfileSnapshot reviewer, ReviewChannel channel) {
        Long subTaskId = subTask.id();
        String prompt = renderPrompt(subTask);
        AgentResult result;
        try {
            AgentTask agentTask = AgentTask.builder()
                    .subTaskId(subTaskId)
                    .systemPrompt("")
                    .userPrompt(prompt)
                    // REF-8.5：context 补 taskId —— 业务面循环入口据此构造 Run 标识与工具解析上下文
                    // （本面 Task 级信息在手，无需额外查询；亦与其余三个进循环面（均带 taskId）同形）
                    .context(Map.of("subTaskId", subTaskId, "taskId", subTask.taskId(),
                            "scene", "subtask_review"))
                    .requiredCapabilities(Map.of())
                    // G-016 契约层技能注入：核验与执行同一技能清单（mergeSkills 恒非 null）
                    .skills(subTaskService.mergeSkills(subTask.id()))
                    .build();
            result = platformAgentExecutionService.executeSync(reviewer.id(), agentTask);
        } catch (Exception e) {
            log.warn("自动核验 LLM 调用异常，子任务停留 REVIEW: subTaskId={}, err={}", subTaskId, e.getMessage());
            recordExecutionFailureQuietly(subTask, reviewer.id(), channel, "exception",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return ExecutionOutcome.empty();
        }
        if (result == null || !result.isSuccess()) {
            log.warn("自动核验 LLM 调用失败，子任务停留 REVIEW: subTaskId={}, err={}",
                    subTaskId, result != null ? result.getErrorMessage() : "null_result");
            recordExecutionFailureQuietly(subTask, reviewer.id(), channel,
                    result == null ? "null_result" : "result_failed",
                    result != null ? result.getErrorMessage() : "");
            return ExecutionOutcome.empty();
        }

        // 对话流双写：核验 Prompt + REVIEWER 分析原文全量落 conversation_message
        // （消息类型随链路来源切换：subtask_review_* / subtask_dual_review_* / subtask_recheck_*），
        // 不可解析时同样保留原始输出（正是人工兜底最需要看的内容）；失败不阻断核验主链路。
        // 三条消息经 writeConversationQuietly 逐条独立 try-catch：单条偶发写入失败
        // （如连接短暂不可用）不连带丢失其余消息，避免复盘只见结论不见请求/思考/分析
        writeConversationQuietly(subTaskId, null,
                "user", "platform", prompt, channel.toolName("prompt"));
        // 推理模型的思考过程单独落一条消息（保留 thinking，供前端动态展示）
        if (result.getThinking() != null && !result.getThinking().isBlank()) {
            writeConversationQuietly(subTaskId, reviewer.id(),
                    "assistant", "agent",
                    result.getThinking(),
                    channel.toolName("thinking"));
        }
        writeConversationQuietly(subTaskId, reviewer.id(),
                "assistant", "agent",
                result.getOutput() != null ? result.getOutput() : "",
                channel.toolName("verdict"));

        VerdictParser.ParseResult parsed = verdictParser.parseWithMeta(result.getOutput());
        SubTaskReviewService.ReviewVerdict verdict = parsed.verdict();
        if (verdict == null) {
            log.warn("自动核验输出不可解析，子任务停留 REVIEW 等人工: subTaskId={}, rawOutput={}",
                    subTaskId, VerdictParser.summarize(result.getOutput(), 300));
            taskTimelineService.recordEvent(subTask.taskId(), subTaskId,
                    TimelineEventCode.SUB_TASK_AUTO_REVIEW_UNPARSEABLE.code(), AgentRole.REVIEWER,
                    reviewer.id(),
                    Map.of("rawOutput", VerdictParser.summarize(result.getOutput(), 300)));
            return ExecutionOutcome.empty();
        }
        List<ReviewVerdictValidator.Finding> findings = collectFindings(subTask, verdict, parsed.looseParse());
        recordVerdictFindings(subTask, reviewer.id(), channel, verdict, parsed.looseParse(), findings);
        return new ExecutionOutcome(verdict, findings);
    }

    /**
     * 静态判定校验（`RV-1` / `RV-2`）+ 覆盖批评（`RV-3`）：两段各由独立开关控制。
     *
     * <p>{@code verdictValidatorEnabled} 关闭 ⇒ 两段都不跑（返回空清单，行为回到改造前，
     * 含不触发 {@code INCONCLUSIVE}）；{@code coverageCriticEnabled} 只关覆盖批评段，
     * 判定形态校验照常。</p>
     */
    private List<ReviewVerdictValidator.Finding> collectFindings(
            SubTaskView subTask, SubTaskReviewService.ReviewVerdict verdict, boolean looseParse) {
        if (!reviewProperties.isVerdictValidatorEnabled()) {
            return List.of();
        }
        List<ReviewVerdictValidator.Finding> findings = new ArrayList<>(
                ReviewVerdictValidator.validate(verdict, subTask.acceptance(), looseParse));
        if (reviewProperties.isCoverageCriticEnabled()) {
            findings.addAll(ReviewCoverageCritic.critique(verdict, subTask.acceptance(),
                    subTask.uncertainties()));
        }
        return findings;
    }

    /**
     * `RV-1` / `RV-2` 判定静态校验埋点。
     *
     * <p>{@code Warn} 级 finding 只观测，不改变判定；{@code Block} 级 finding（当前唯一：
     * 「驳回却给满分」）由调用方按 `RV-2` 落 {@code INCONCLUSIVE}，事件 payload 的
     * {@code enforced=true} 即声明「本轮判定已被拦截」。静态校验开关关闭时
     * {@link #collectFindings} 返回空清单，本方法直接返回（零事件）。</p>
     *
     * <p>事件写入失败只降级告警：静态校验是旁路观测，不得影响核验主链路。</p>
     */
    private void recordVerdictFindings(SubTaskView subTask, Long reviewerAgentId, ReviewChannel channel,
                                       SubTaskReviewService.ReviewVerdict verdict, boolean looseParse,
                                       List<ReviewVerdictValidator.Finding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        long blockCount = findings.stream()
                .filter(f -> f.severity() == ReviewVerdictValidator.Severity.BLOCK)
                .count();
        List<Map<String, Object>> items = findings.stream()
                .map(f -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("severity", f.severity().name());
                    item.put("code", f.code());
                    item.put("detail", f.detail());
                    return item;
                })
                .toList();
        Map<String, Object> payload = new HashMap<>();
        payload.put("channel", channel != null ? channel.name() : "");
        payload.put("pass", verdict.getPass());
        payload.put("score", verdict.getScore());
        payload.put("looseParse", looseParse);
        payload.put("blockCount", blockCount);
        payload.put("warnCount", findings.size() - blockCount);
        payload.put("enforced", blockCount > 0);
        payload.put("findings", items);
        try {
            taskTimelineService.recordEvent(subTask.taskId(), subTask.id(),
                    TimelineEventCode.SUB_TASK_AUTO_REVIEW_VERDICT_WARN.code(), AgentRole.REVIEWER,
                    reviewerAgentId, payload);
        } catch (Exception e) {
            log.warn("核验判定告警事件写入失败（观测降级，不阻断核验）: subTaskId={}, err={}",
                    subTask.id(), e.getMessage());
        }
        log.info("核验判定静态校验命中: subTaskId={}, reviewerAgentId={}, block={}, warn={}",
                subTask.id(), reviewerAgentId, blockCount, findings.size() - blockCount);
    }

    /**
     * `RV-4` 核验调用失败事件（异常 / 结果失败），与「输出不可解析」区分。
     *
     * <p>此前失败路径只有 {@code log.warn}，时间线上看不到「这一轮根本没判」，
     * 事后只能从「没有核验事件」反推。事件按链路来源（SINGLE/DUAL/RECHECK）记 {@code channel}，
     * 双审单侧失败与抽检失败因此可分辨。写入失败只降级告警，不影响「返回 null 停留 REVIEW」契约。</p>
     */
    private void recordExecutionFailureQuietly(SubTaskView subTask, Long reviewerAgentId,
                                               ReviewChannel channel, String reason, String error) {
        try {
            taskTimelineService.recordEvent(subTask.taskId(), subTask.id(),
                    TimelineEventCode.SUB_TASK_AUTO_REVIEW_EXECUTION_FAILED.code(), AgentRole.REVIEWER,
                    reviewerAgentId,
                    Map.of("channel", channel != null ? channel.name() : "",
                            "reason", reason,
                            "error", VerdictParser.summarize(error, 300)));
        } catch (Exception e) {
            log.warn("核验失败事件写入失败（观测降级，不阻断核验）: subTaskId={}, err={}",
                    subTask.id(), e.getMessage());
        }
    }

    /** 核验对话流单条消息写入：失败仅告警（WARN 带异常栈）不阻断核验主链路。 */
    private void writeConversationQuietly(Long subTaskId, Long senderId,
                                          String role, String senderType,
                                          String content, String toolName) {
        try {
            conversationService.addMessage(subTaskId, senderId, role, senderType, content, toolName);
        } catch (Exception e) {
            log.warn("核验对话流写入失败（不阻断核验）: subTaskId={}, toolName={}, err={}",
                    subTaskId, toolName, e.getMessage(), e);
        }
    }

    /**
     * 加载核验 Prompt 模板并替换占位符（证据/附件占位由装配器产出）。
     * G-011 D7：增 {{CONSTRAINTS}} + {{UNCERTAINTIES}} 渲染（空时「（无）」），
     * 清偿 G-010 后置缺口③（审查侧 constraints 此前未消费）。
     * G-010 收口：增 {{REQUIRED_SKILLS}} 渲染——审查侧此前只拿到合并后的技能<b>能力</b>
     * （{@code mergeSkills} 注入 AgentTask.skills），看不到子任务<b>要求</b>哪些技能，
     * 因而无法核验「要求的交付形态是否出现」。
     *
     * <p>包可见：供 {@code ReviewPromptRenderTest} 断言「模板占位符 ↔ 渲染实现」零漂移
     * （模板新增占位符而实现未替换时，残留的 {@code {{…}}} 会被被测出）。</p>
     */
    String renderPrompt(SubTaskView subTask) {
        ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取核验 Prompt 模板失败: " + e.getMessage(), e);
        }
        return template
                .replace("{{SUB_TASK_TITLE}}", VerdictParser.nullToEmpty(subTask.title()))
                .replace("{{SUB_TASK_CONTENT}}", VerdictParser.nullToEmpty(subTask.content()))
                .replace("{{DELIVERABLE}}", VerdictParser.nullToEmpty(subTask.deliverable()))
                .replace("{{ACCEPTANCE}}", VerdictParser.nullToEmpty(subTask.acceptance()))
                .replace("{{CONSTRAINTS}}", renderConstraints(subTask))
                .replace("{{REQUIRED_SKILLS}}", renderRequiredSkills(subTask))
                .replace("{{UNCERTAINTIES}}", renderUncertainties(subTask))
                .replace("{{EXECUTION_OUTPUT}}", reviewEvidenceAssembler.extractExecutionOutput(subTask))
                .replace("{{ATTACHMENT_LIST}}", reviewEvidenceAssembler.buildAttachmentList(subTask))
                .replace("{{ATTACHMENT_CONTENT}}", reviewEvidenceAssembler.buildAttachmentContent(subTask))
                .replace("{{VERIFICATION_SIGNAL}}",
                        reviewEvidenceAssembler.verificationSignal(reviewEvidenceAssembler.extractRawOutput(subTask)));
    }

    /** D7：执行约束渲染——非空注入「执行约束：…」，空时「（无）」（G-010 缺口③清偿）。 */
    private String renderConstraints(SubTaskView subTask) {
        return subTask.constraints() != null && !subTask.constraints().isBlank()
                ? subTask.constraints() : "（无）";
    }

    /**
     * G-010：技能要求渲染——非空逐项以「、」连接（过滤空白项），空时「（无）」。
     *
     * <p>只渲染<b>要求</b>（{@code sub_task.required_skills}），不渲染 Agent 实际持有的技能集：
     * 后者已由 {@code mergeSkills} 作为能力注入，审查侧需要的是「本该交付什么形态」的判据。</p>
     */
    private String renderRequiredSkills(SubTaskView subTask) {
        if (subTask.requiredSkills() == null || subTask.requiredSkills().isEmpty()) {
            return "（无）";
        }
        String joined = subTask.requiredSkills().stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("、"));
        return joined.isEmpty() ? "（无）" : joined;
    }

    /** D7：不确定性申报渲染——逐条「[kind] note」，空时「（无）」（空白 note 条目跳过）。 */
    private String renderUncertainties(SubTaskView subTask) {
        if (subTask.uncertainties() == null || subTask.uncertainties().isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (UncertaintyView u : subTask.uncertainties()) {
            if (u.note() == null || u.note().isBlank()) {
                continue;
            }
            sb.append("- [").append(u.kind()).append("] ").append(u.note()).append('\n');
        }
        return sb.length() > 0 ? sb.toString().trim() : "（无）";
    }
}
