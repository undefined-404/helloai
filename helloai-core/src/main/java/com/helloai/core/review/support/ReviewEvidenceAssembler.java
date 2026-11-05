package com.helloai.core.review.support;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.ReviewResult;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.shared.util.TextTruncator;
import com.helloai.core.shared.util.VerificationFence;
import com.helloai.core.task.port.AttachmentView;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskDispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动核验证据装配器：把子任务的"产出证据"装配为核验 Prompt 可消费的内容。
 *
 * <p>职责边界（自 {@code SubTaskReviewServiceImpl} 拆分）：</p>
 * <ul>
 *     <li>证据硬检查 {@link #checkEvidence(SubTaskView)}：fail-close 判定声称的交付物
 *         是否有物化附件/可读产出支撑，含执行密集任务物化竞态补偿；</li>
 *     <li>产出提取 {@link #extractExecutionOutput(SubTaskView)} / {@link #extractRawOutput(SubTaskView)}
 *         与围栏证据信号 {@link #verificationSignal(String)}；</li>
 *     <li>附件族装配 {@link #buildAttachmentList(SubTaskView)} / {@link #buildAttachmentContent(SubTaskView)}：
 *         清单 + 文本正文限额注入 + 媒体可见性标注（方案3 F2 与硬化条款）。</li>
 * </ul>
 *
 * <p>纯装配无状态：不触发任何状态变更，只读附件/子任务数据并渲染文本。</p>
 */
@Slf4j
@Component
public class ReviewEvidenceAssembler {

    private final AttachmentService attachmentService;
    private final AgentDispatchProperties dispatchProperties;

    /**
     * 驳回快照冻结证据总字符上限：超过则不冻结（复判退回漂移跳过）。
     * 证据五段本身已按核验口径截断（附件总限额 200000 字符），本上限只防异常数据把 JSONB 撑大。
     */
    static final int FROZEN_EVIDENCE_MAX_CHARS = 300_000;

    /** 显式全参构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在 IDE 增量编译里漏抓新增
     * final 字段的坑：显式列为 Spring DI 唯一依据）。 */
    @Autowired
    public ReviewEvidenceAssembler(AttachmentService attachmentService,
                                   AgentDispatchProperties dispatchProperties) {
        this.attachmentService = attachmentService;
        this.dispatchProperties = dispatchProperties;
    }

    /**  证据检查结果。 */
    public record EvidenceCheckResult(boolean ok, String reason, int attachmentCount, boolean outputPresent) {
    }

    /**
     *  证据硬检查：子任务声称的交付物必须有物化附件/可读产出支撑（fail-close）。
     *
     * <p>判定规则：</p>
     * <ul>
     *   <li>无可读附件且执行产出为空 → {@code no_output_no_attachment}：连产出本体
     *       都没有的编造提交，直接拦截；</li>
     *   <li>执行密集任务（交付物声明为脚本/程序/文件）无可读物化附件 →
     *       {@code execution_dense_no_attachment}：产出文本仅为描述性文字，无真实
     *       物化产物支撑，拦截（fail-close——宁可人工介入，不放行存疑产出）；</li>
     *   <li>其余（可读附件存在，或非执行密集任务有文本产出）→ 放行，附件清单注入
     *       核验 Prompt 由 LLM 核对声称交付物与附件的对应关系。</li>
     * </ul>
     *
     * <p>物化在结果回报事务 afterCommit 同步执行、自动核验异步启动，两者存在毫秒级
     * 竞态；执行密集任务未发现可读附件时等待 {@code reviewEvidenceCheckWaitMs} 后重查
     * 一次，避免物化未完成被误判为无证据。</p>
     */
    public EvidenceCheckResult checkEvidence(SubTaskView subTask) {
        List<AttachmentView> readable = readableAttachments(subTask.id());
        String output = SubTaskOutputExtractor.extractExecutionOutput(subTask.context());
        boolean hasOutput = output != null && !output.isBlank();
        boolean isDense = SubTaskDispatchService.isExecutionDense(subTask);

        if (readable.isEmpty()) {
            // 竞态补偿：执行密集 + 有产出文本时等待窗口重查（物化在 afterCommit 同步完成）
            if (isDense && hasOutput) {
                int waitMs = dispatchProperties.getReviewEvidenceCheckWaitMs();
                if (waitMs > 0) {
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    readable = readableAttachments(subTask.id());
                }
            }
            if (readable.isEmpty()) {
                if (!hasOutput) {
                    return new EvidenceCheckResult(false, "no_output_no_attachment", 0, false);
                }
                if (isDense) {
                    return new EvidenceCheckResult(false, "execution_dense_no_attachment",
                            readable.size(), true);
                }
            }
        }
        return new EvidenceCheckResult(true, null, readable.size(), hasOutput);
    }

    /** 子任务可读附件列表（local:// 平台直读产物；仅 ACTIVE 有效版本——同名多版本在
     * {@link com.helloai.core.task.service.impl.AttachmentServiceImpl#register} 时
     * 已自动去活，核验只认当前最新上传，避免旧版本冲突污染判定；list 返回 null 防御按空处理）。 */
    private List<AttachmentView> readableAttachments(Long subTaskId) {
        List<AttachmentView> attachments = attachmentService.listActiveViews(subTaskId);
        if (attachments == null) {
            return List.of();
        }
        return attachments.stream()
                .filter(AttachmentView::contentLoadable)
                .toList();
    }

    /**
     * 构建判定快照（`RV-5`）：与核验证据同一来源（可读物化附件 + 执行产出），
     * 落库时与 `review_record` 同事务（见 `ReviewService#recordAutoReview`）。
     *
     * <p>体积超阈值只告警不阻断——快照是观测/复判依据，不得因为它自身的大小改变判定。</p>
     */
    public Map<String, Object> buildReviewSnapshot(SubTaskView subTask) {
        return buildReviewSnapshot(subTask, null, null);
    }

    /**
     * 构建判定快照，并在**驳回时可冻结原判证据**（`RV-6`）。
     *
     * <p>驳回记录返工后附件失效、产出被覆盖，只存引用/摘要无法复判；这里把核验 Prompt 实际
     * 消费的五段证据文本（见 {@link ReviewEvidenceView}）原样冻结进 JSONB，复判直接复用。
     * 仅在 {@code result == REJECTED} 且证据非空时冻结——通过记录原对象不会失效，不需要额外存储。</p>
     *
     * <p>冻结体积超过 {@link #FROZEN_EVIDENCE_MAX_CHARS} 时不冻结（快照仍落，复判按
     * {@code snapshot_drift} 跳过）——不拿不完整的原判对象做误伤率度量。</p>
     *
     * @param result   本次核验结论；null / 非 REJECTED 不冻结
     * @param evidence 判定当时实际渲染的证据；null 不冻结
     */
    public Map<String, Object> buildReviewSnapshot(SubTaskView subTask, ReviewResult result,
                                                   ReviewEvidenceView evidence) {
        Map<String, Object> frozen = result == ReviewResult.REJECTED ? freezeEvidence(subTask, evidence) : null;
        Map<String, Object> snapshot = ReviewSnapshotBuilder.build(
                readableAttachments(subTask.id()),
                SubTaskOutputExtractor.extractExecutionOutput(subTask.context()),
                frozen);
        int bytes = ReviewSnapshotBuilder.estimatedBytes(snapshot);
        if (bytes > ReviewSnapshotBuilder.WARN_BYTES) {
            log.warn("核验快照体积超阈值（存储增长观测）: subTaskId={}, bytes={}, threshold={}",
                    subTask.id(), bytes, ReviewSnapshotBuilder.WARN_BYTES);
        }
        return snapshot;
    }

    /**
     * 读取驳回快照里冻结的原判证据；没有冻结（历史记录 / 通过记录 / 超限）时返回 null，
     * 调用方应退回「快照漂移对账」路径。
     */
    public ReviewEvidenceView frozenEvidenceView(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        Object node = snapshot.get("frozenEvidence");
        if (!(node instanceof Map<?, ?> frozen)) {
            return null;
        }
        Object executionOutput = frozen.get("executionOutput");
        Object attachmentList = frozen.get("attachmentList");
        Object attachmentContent = frozen.get("attachmentContent");
        if (executionOutput == null && attachmentList == null && attachmentContent == null) {
            return null;
        }
        return new ReviewEvidenceView(text(executionOutput), text(attachmentList), text(attachmentContent),
                text(frozen.get("verificationSignal")), text(frozen.get("selfReviewSignal")));
    }

    private Map<String, Object> freezeEvidence(SubTaskView subTask, ReviewEvidenceView evidence) {
        if (evidence == null) {
            return null;
        }
        int chars = evidence.totalChars();
        if (chars > FROZEN_EVIDENCE_MAX_CHARS) {
            log.warn("驳回快照冻结证据超限，退回漂移跳过（不拿不完整对象度量误伤率）: "
                            + "subTaskId={}, chars={}, limit={}",
                    subTask.id(), chars, FROZEN_EVIDENCE_MAX_CHARS);
            return null;
        }
        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put("executionOutput", evidence.executionOutput());
        frozen.put("attachmentList", evidence.attachmentList());
        frozen.put("attachmentContent", evidence.attachmentContent());
        frozen.put("verificationSignal", evidence.verificationSignal());
        frozen.put("selfReviewSignal", evidence.selfReviewSignal());
        frozen.put("chars", chars);
        return frozen;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 抽检前对账：当前证据是否仍是原判定对象（`RV-5`，见 {@link ReviewSnapshotMatcher}）。 */
    public ReviewSnapshotMatcher.Drift checkSnapshotDrift(Map<String, Object> snapshot, SubTaskView current) {
        return ReviewSnapshotMatcher.check(snapshot, readableAttachments(current.id()),
                SubTaskOutputExtractor.extractExecutionOutput(current.context()));
    }

    /**
     *  附件清单：核验 Prompt 注入子任务全部附件（可读 local:// 产物标注平台直读，
     * 外部存储标注不可直读），供核验 LLM 核对"声称交付物 ↔ 真实附件"的对应关系——
     * 声称"文件 203 行 errors=0"但附件清单无对应文件时判不达标。
     */
    public String buildAttachmentList(SubTaskView subTask) {
        List<AttachmentView> attachments = attachmentService.listActiveViews(subTask.id());
        if (attachments == null || attachments.isEmpty()) {
            return "（无物化附件）";
        }
        StringBuilder sb = new StringBuilder();
        for (AttachmentView att : attachments) {
            String size = att.fileSize() != null ? att.fileSize() + " bytes" : "?";
            String readable = att.contentLoadable()
                    ? "平台可直读" : "外部存储（平台不可直读）";
            String type = att.fileType() != null ? att.fileType() : "other";
            sb.append("- ").append(att.fileName())
                    .append("（").append(type).append(", ").append(size).append(", ")
                    .append(readable).append("）\n");
        }
        return sb.toString().trim();
    }

    /**
     * 核验侧附件内容注入（方案3 F2）：把可直读物化附件（local:// 与 minio://）正文截断后
     * 注入核验 Prompt，让 Reviewer 基于真实文件内容核对"声称交付物 ↔ 文件正文 ↔ 验收标准"，
     * 而非仅凭文件名猜测（消除"Reviewer 审查靠摘要+文件名"的幻觉缺口）。
     *
     * <p>限额策略：每附件 64000 字符、总计 200000 字符（常量单源见 {@link AttachmentContentPolicy}，
     * 2026-10-03 由 8000/24000 上调以匹配官方 DeepSeek 64K 上下文），
     * 超限截断并标注；不可直读/读取失败/
     * 空内容附件不注入正文（清单仍全量展示）；开关 {@code helloai.dispatch.attachment-content-enabled}
     * 关闭时退化为仅清单（与开关引入前行为一致）。</p>
     *
     * <p>文本硬化：仅对文本类附件注入正文，图片/音频/视频等二进制附件绝不按文本读取
     * （避免二进制乱码进 Prompt 并吞占限额）；提交含媒体附件时前置注入媒体可见性标注
     * （独立于注入开关），告知核验 LLM 原内容不可见、文字声称从严核验。</p>
     */
    public String buildAttachmentContent(SubTaskView subTask) {
        String mediaNote = buildMediaVisibilityNote(subTask.id());
        if (!dispatchProperties.isAttachmentContentEnabled()) {
            return mediaNote + "（附件内容注入已关闭，仅见清单）";
        }
        List<AttachmentView> attachments = readableAttachments(subTask.id());
        if (attachments.isEmpty()) {
            return mediaNote + "（无平台可直读附件，无法核对文件正文）";
        }
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        boolean truncated = false;
        boolean totalExceeded = false;
        for (AttachmentView att : attachments) {
            if (!AttachmentContentPolicy.isTextual(att.mimeType(), att.fileName())) {
                // 非文本附件（图片/音频/视频等）不注入正文，避免二进制乱码；媒体可见性标注已覆盖
                continue;
            }
            String content = readAttachmentContent(att);
            if (content == null) {
                sb.append("### ").append(att.fileName())
                        .append("（").append(att.fileType() != null ? att.fileType() : "other")
                        .append("，内容不可读/为空）\n");
                continue;
            }
            String attName = att.fileName() != null ? att.fileName() : "unknown";
            int originalChars = content.length();
            boolean perFileTruncated = false;
            if (content.length() > AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT) {
                // ★2026-10-03 R2 修复：截断前先基于**全文**提取 Markdown 章节结构，
                // 让 Reviewer 即使看不到后半段正文，也能知道该文件还有哪些章节——
                // 避免「目录树/教程大纲」等验收标准直接依赖的章节因排在后半段被静默丢弃
                // （真机复现：fastapi_contract.md 2.9 万字符被截到 §7.3，目录树/教程大纲不可见 → 误判驳回）。
                String structureOutline = buildStructureOutline(content);
                // P-1 防御：行边界回退截断（避免拦腰切断 URL/代码行）
                content = TextTruncator.truncateAtLineBoundary(
                        content, AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT);
                if (structureOutline != null) {
                    content = content + structureOutline;
                }
                truncated = true;
                perFileTruncated = true;
            }
            if (totalChars + content.length() > AttachmentContentPolicy.ATTACHMENT_CONTENT_TOTAL_LIMIT) {
                int remaining = AttachmentContentPolicy.ATTACHMENT_CONTENT_TOTAL_LIMIT - totalChars;
                if (remaining > 0) {
                    String partial = TextTruncator.truncateAtLineBoundary(content, remaining);
                    appendAttachmentContent(sb, att, partial);
                    // P1-4-c：结构化标注行——让核验模型精确知道「哪些字节不可见」，
                    // 而非仅凭自然语言标记（后者无法被消费方机器读取）。
                    sb.append("[TRUNCATED] file=").append(attName)
                            .append(" shown=").append(partial.length())
                            .append(" total=").append(originalChars)
                            .append(" reason=total_limit\n");
                    truncated = true;
                }
                totalExceeded = true;
                break;
            }
            totalChars += content.length();
            appendAttachmentContent(sb, att, content);
            if (perFileTruncated) {
                // P1-4-c：单附件超限的结构化标注行
                sb.append("[TRUNCATED] file=").append(attName)
                        .append(" shown=").append(content.length())
                        .append(" total=").append(originalChars)
                        .append(" reason=per_file_limit\n");
            }
        }
        if (truncated) {
            sb.append("（部分附件内容已截断至限额）\n");
        }
        if (totalExceeded) {
            sb.append("（附件内容总计超出限额，后续附件仅见清单）");
        }
        return (mediaNote + sb).trim();
    }

    /** 单附件内容段：标题行（文件名/类型/大小）+ 正文。 */
    private void appendAttachmentContent(StringBuilder sb, AttachmentView att, String content) {
        String size = att.fileSize() != null ? att.fileSize() + " bytes" : "?";
        String type = att.fileType() != null ? att.fileType() : "other";
        sb.append("### ").append(att.fileName())
                .append("（").append(type).append("，").append(size).append("）\n")
                .append(content).append("\n");
    }

    /**
     * 提取 Markdown 章节结构大纲（R2 修复）：扫描全文的行首 {@code #} 标题，
     * 生成缩进的「章节树」，供超限截断后追加，让 Reviewer 知道后半段还有哪些章节。
     *
     * <p>仅提取标题行本身（每行极短），整体开销可控（通常几十~上百字符），
     * 不占用附件正文限额之外的实质预算；无标题的纯文本/代码文件返回 {@code null}
     * （保持原「从头截断」行为不变）。</p>
     *
     * @param fullContent 附件全文（截断前）
     * @return 结构大纲块（含前导空行），无可提取标题时返回 {@code null}
     */
    private String buildStructureOutline(String fullContent) {
        if (fullContent == null || fullContent.isEmpty()) {
            return null;
        }
        StringBuilder outline = new StringBuilder();
        int headingCount = 0;
        for (String line : fullContent.split("\n", -1)) {
            int level = 0;
            int idx = 0;
            while (idx < line.length() && line.charAt(idx) == '#') {
                level++;
                idx++;
            }
            // 仅认行首连续 # 后跟空格的 ATX 标题（# 空格 标题），忽略 # 之后非空格的（如 #include）
            if (level >= 1 && level <= 6 && idx < line.length() && line.charAt(idx) == ' ') {
                String title = line.substring(idx).trim();
                if (!title.isEmpty()) {
                    outline.append("\n").append("  ".repeat(level - 1))
                            .append("- ").append(title);
                    headingCount++;
                }
            }
        }
        if (headingCount == 0) {
            return null;
        }
        return "\n\n---\n（该文件后续章节结构，正文已截断）\n" + outline + "\n";
    }

    /** 读取可直读附件正文；不可读/为空返回 null（注入"内容不可读"标注，不中断整体注入）。 */
    private String readAttachmentContent(AttachmentView att) {
        try {
            byte[] bytes = attachmentService.loadContent(att.id());
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("附件内容读取失败，仅注入清单: attachmentId={}, err={}", att.id(), e.getMessage());
            return null;
        }
    }

    /**
     * 媒体可见性标注：提交含图片/音频/视频附件时，显式告知核验 LLM 当前链路无法查看
     * 原内容、相关文字声称从严核验评分保守。"看不到媒体"与内容注入开关无关，
     * 故开关关闭时同样注入；无媒体附件返回空串。
     */
    private String buildMediaVisibilityNote(Long subTaskId) {
        List<AttachmentView> attachments = attachmentService.listActiveViews(subTaskId);
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        List<String> mediaNames = attachments.stream()
                .filter(att -> AttachmentContentPolicy.isMedia(att.mimeType(), att.fileName()))
                .map(AttachmentView::fileName)
                .toList();
        if (mediaNames.isEmpty()) {
            return "";
        }
        return "本提交含 " + mediaNames.size() + " 个媒体附件（" + String.join("、", mediaNames)
                + "）。当前核验链路无法查看其原始内容；与之相关的文字声称请从严核验、评分保守。\n";
    }

    /**
     * 从 context.lastExecution.output 提取执行产出，缺失时给出占位说明。
     */
    public String extractExecutionOutput(SubTaskView subTask) {
        String raw = extractRawOutput(subTask);
        if (!raw.isBlank()) {
            return summarize(raw, AttachmentContentPolicy.OUTPUT_SUMMARY_LIMIT);
        }
        return "（执行产出为空或缺失，请据交付物/验收标准审慎判定）";
    }

    /** 取执行产出原文（不截断），供围栏证据信号检测使用。 */
    public String extractRawOutput(SubTaskView subTask) {
        Map<String, Object> ctx = subTask.context();
        if (ctx != null && ctx.get("lastExecution") instanceof Map<?, ?> lastExecution) {
            Object output = lastExecution.get("output");
            if (output != null) {
                return output.toString();
            }
        }
        return "";
    }

    /**
     * 围栏证据信号：检测提交是否携带 VERIFICATION 段（基于截断前原文）。
     *
     * <p>仅检测不拦截——无证据提交不拒收，但注入"从严核验"指令，
     * 与 executor SKILL 的 fail-close 条款形成闭环。</p>
     */
    public String verificationSignal(String rawOutput) {
        // REF-8.4 ③：匹配器与 VerificationFence 共用单一来源，防「两份围栏判定」漂移
        boolean hasEvidence = VerificationFence.containsMarker(rawOutput);
        return hasEvidence
                ? "该提交携带验证证据（VERIFICATION 段）：请核对证据中命令/输出/结论与交付物的一致性，"
                        + "证据与结论矛盾或明显伪造的按不达标处理。"
                : "该提交未携带验证证据（无 VERIFICATION 段）：请从严核验、评分保守；"
                        + "仅凭产出文本无法确认满足验收标准时不得判 pass=true。";
    }

    /** 长文本摘要：超限截断并加省略号。 */
    private static String summarize(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "...";
    }
}
