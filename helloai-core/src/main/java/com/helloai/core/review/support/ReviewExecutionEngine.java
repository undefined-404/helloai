package com.helloai.core.review.support;

import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Uncertainty;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final ConversationService conversationService;
    private final TaskTimelineService taskTimelineService;
    private final VerdictParser verdictParser;
    private final ReviewEvidenceAssembler reviewEvidenceAssembler;

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
     * @return 结构化判定；LLM 调用失败/超时/输出不可解析时返回 null
     */
    public SubTaskReviewService.ReviewVerdict execute(SubTask subTask, Agent reviewer) {
        return execute(subTask, reviewer, ReviewChannel.SINGLE);
    }

    /**
     * 单次核验（不改状态不落库）：同 {@link #execute(SubTask, Agent)}，按链路来源
     * 切换对话流消息类型前缀（单审/双审/抽检三态可分辨）。
     *
     * @param subTask  待核验子任务
     * @param reviewer 核验 Reviewer Agent
     * @param channel  链路来源（SINGLE / DUAL / RECHECK），决定消息类型前缀
     * @return 结构化判定；LLM 调用失败/超时/输出不可解析时返回 null
     */
    public SubTaskReviewService.ReviewVerdict execute(SubTask subTask, Agent reviewer, ReviewChannel channel) {
        Long subTaskId = subTask.getId();
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
            return null;
        }
        if (result == null || !result.isSuccess()) {
            log.warn("自动核验 LLM 调用失败，子任务停留 REVIEW: subTaskId={}, err={}",
                    subTaskId, result != null ? result.getErrorMessage() : "null_result");
            return null;
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
            writeConversationQuietly(subTaskId, reviewer.getId(),
                    "assistant", "agent",
                    result.getThinking(),
                    channel.toolName("thinking"));
        }
        writeConversationQuietly(subTaskId, reviewer.getId(),
                "assistant", "agent",
                result.getOutput() != null ? result.getOutput() : "",
                channel.toolName("verdict"));

        SubTaskReviewService.ReviewVerdict verdict = verdictParser.parseVerdict(result.getOutput());
        if (verdict == null) {
            log.warn("自动核验输出不可解析，子任务停留 REVIEW 等人工: subTaskId={}, rawOutput={}",
                    subTaskId, VerdictParser.summarize(result.getOutput(), 300));
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                    "sub_task_auto_review_unparseable", AgentRole.REVIEWER,
                    reviewer.getId(),
                    Map.of("rawOutput", VerdictParser.summarize(result.getOutput(), 300)));
            return null;
        }
        return verdict;
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
     */
    private String renderPrompt(SubTask subTask) {
        ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读取核验 Prompt 模板失败: " + e.getMessage(), e);
        }
        return template
                .replace("{{SUB_TASK_TITLE}}", VerdictParser.nullToEmpty(subTask.getTitle()))
                .replace("{{SUB_TASK_CONTENT}}", VerdictParser.nullToEmpty(subTask.getContent()))
                .replace("{{DELIVERABLE}}", VerdictParser.nullToEmpty(subTask.getDeliverable()))
                .replace("{{ACCEPTANCE}}", VerdictParser.nullToEmpty(subTask.getAcceptance()))
                .replace("{{CONSTRAINTS}}", renderConstraints(subTask))
                .replace("{{UNCERTAINTIES}}", renderUncertainties(subTask))
                .replace("{{EXECUTION_OUTPUT}}", reviewEvidenceAssembler.extractExecutionOutput(subTask))
                .replace("{{ATTACHMENT_LIST}}", reviewEvidenceAssembler.buildAttachmentList(subTask))
                .replace("{{ATTACHMENT_CONTENT}}", reviewEvidenceAssembler.buildAttachmentContent(subTask))
                .replace("{{VERIFICATION_SIGNAL}}",
                        reviewEvidenceAssembler.verificationSignal(reviewEvidenceAssembler.extractRawOutput(subTask)));
    }

    /** D7：执行约束渲染——非空注入「执行约束：…」，空时「（无）」（G-010 缺口③清偿）。 */
    private String renderConstraints(SubTask subTask) {
        return subTask.getConstraints() != null && !subTask.getConstraints().isBlank()
                ? subTask.getConstraints() : "（无）";
    }

    /** D7：不确定性申报渲染——逐条「[kind] note」，空时「（无）」（空白 note 条目跳过）。 */
    private String renderUncertainties(SubTask subTask) {
        if (subTask.getUncertainties() == null || subTask.getUncertainties().isEmpty()) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder();
        for (Uncertainty u : subTask.getUncertainties()) {
            if (u.getNote() == null || u.getNote().isBlank()) {
                continue;
            }
            sb.append("- [").append(u.getKind()).append("] ").append(u.getNote()).append('\n');
        }
        return sb.length() > 0 ? sb.toString().trim() : "（无）";
    }
}
