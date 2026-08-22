package com.helloai.core.review.support;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskDispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动核验证据装配器：把子任务的"产出证据"装配为核验 Prompt 可消费的内容。
 *
 * <p>职责边界（自 {@code SubTaskReviewServiceImpl} 拆分）：</p>
 * <ul>
 *     <li>证据硬检查 {@link #checkEvidence(SubTask)}：fail-close 判定声称的交付物
 *         是否有物化附件/可读产出支撑，含执行密集任务物化竞态补偿；</li>
 *     <li>产出提取 {@link #extractExecutionOutput(SubTask)} / {@link #extractRawOutput(SubTask)}
 *         与围栏证据信号 {@link #verificationSignal(String)}；</li>
 *     <li>附件族装配 {@link #buildAttachmentList(SubTask)} / {@link #buildAttachmentContent(SubTask)}：
 *         清单 + 文本正文限额注入 + 媒体可见性标注（方案3 F2 与硬化条款）。</li>
 * </ul>
 *
 * <p>纯装配无状态：不触发任何状态变更，只读附件/子任务数据并渲染文本。</p>
 */
@Slf4j
@Component
public class ReviewEvidenceAssembler {

    private static final int OUTPUT_SUMMARY_LIMIT = 4000;

    /** 附件内容注入限额（方案3 F2）：每附件 8000 字符，超限截断并标注。 */
    private static final int ATTACHMENT_CONTENT_PER_FILE_LIMIT = 8000;
    /** 附件内容注入限额（方案3 F2）：总计 24000 字符，超限停止注入后续附件正文。 */
    private static final int ATTACHMENT_CONTENT_TOTAL_LIMIT = 24000;
    /** 文本族 MIME 精确值集（text/* 前缀另判）：命中才允许注入附件正文。 */
    private static final Set<String> TEXTUAL_MIME_EXACT = Set.of(
            "application/json", "application/xml", "application/x-yaml", "application/yaml", "application/sql");
    /** 文本扩展名兜底集（mimeType 缺失或 octet-stream 时用）：命中才允许注入附件正文。 */
    private static final Set<String> TEXTUAL_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "log", "json", "xml", "yaml", "yml", "csv", "tsv", "sql",
            "java", "py", "js", "ts", "sh", "ps1", "html", "css", "properties", "ini", "toml");
    /** 媒体类 MIME 前缀：图片/音频/视频。 */
    private static final List<String> MEDIA_MIME_PREFIXES = List.of("image/", "audio/", "video/");
    /** 媒体扩展名兜底集（mimeType 缺失或 octet-stream 时用）。 */
    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "mp3", "wav", "m4a", "ogg", "flac",
            "mp4", "avi", "mov", "mkv", "webm");

    private final AttachmentService attachmentService;
    private final AgentDispatchProperties dispatchProperties;

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
    public EvidenceCheckResult checkEvidence(SubTask subTask) {
        List<Attachment> readable = readableAttachments(subTask.getId());
        String output = SubTaskOutputExtractor.extractExecutionOutput(subTask);
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
                    readable = readableAttachments(subTask.getId());
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
    private List<Attachment> readableAttachments(Long subTaskId) {
        List<Attachment> attachments = attachmentService.listActive(subTaskId);
        if (attachments == null) {
            return List.of();
        }
        return attachments.stream()
                .filter(attachmentService::isContentLoadable)
                .toList();
    }

    /**
     *  附件清单：核验 Prompt 注入子任务全部附件（可读 local:// 产物标注平台直读，
     * 外部存储标注不可直读），供核验 LLM 核对"声称交付物 ↔ 真实附件"的对应关系——
     * 声称"文件 203 行 errors=0"但附件清单无对应文件时判不达标。
     */
    public String buildAttachmentList(SubTask subTask) {
        List<Attachment> attachments = attachmentService.listActive(subTask.getId());
        if (attachments == null || attachments.isEmpty()) {
            return "（无物化附件）";
        }
        StringBuilder sb = new StringBuilder();
        for (Attachment att : attachments) {
            String size = att.getFileSize() != null ? att.getFileSize() + " bytes" : "?";
            String readable = attachmentService.isContentLoadable(att)
                    ? "平台可直读" : "外部存储（平台不可直读）";
            String type = att.getFileType() != null ? att.getFileType() : "other";
            sb.append("- ").append(att.getFileName())
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
     * <p>限额策略：每附件 8000 字符、总计 24000 字符，超限截断并标注；不可直读/读取失败/
     * 空内容附件不注入正文（清单仍全量展示）；开关 {@code helloai.dispatch.attachment-content-enabled}
     * 关闭时退化为仅清单（与开关引入前行为一致）。</p>
     *
     * <p>文本硬化：仅对文本类附件注入正文，图片/音频/视频等二进制附件绝不按文本读取
     * （避免二进制乱码进 Prompt 并吞占限额）；提交含媒体附件时前置注入媒体可见性标注
     * （独立于注入开关），告知核验 LLM 原内容不可见、文字声称从严核验。</p>
     */
    public String buildAttachmentContent(SubTask subTask) {
        String mediaNote = buildMediaVisibilityNote(subTask.getId());
        if (!dispatchProperties.isAttachmentContentEnabled()) {
            return mediaNote + "（附件内容注入已关闭，仅见清单）";
        }
        List<Attachment> attachments = readableAttachments(subTask.getId());
        if (attachments.isEmpty()) {
            return mediaNote + "（无平台可直读附件，无法核对文件正文）";
        }
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        boolean truncated = false;
        boolean totalExceeded = false;
        for (Attachment att : attachments) {
            if (!isTextualAttachment(att)) {
                // 非文本附件（图片/音频/视频等）不注入正文，避免二进制乱码；媒体可见性标注已覆盖
                continue;
            }
            String content = readAttachmentContent(att);
            if (content == null) {
                sb.append("### ").append(att.getFileName())
                        .append("（").append(att.getFileType() != null ? att.getFileType() : "other")
                        .append("，内容不可读/为空）\n");
                continue;
            }
            if (content.length() > ATTACHMENT_CONTENT_PER_FILE_LIMIT) {
                content = content.substring(0, ATTACHMENT_CONTENT_PER_FILE_LIMIT);
                truncated = true;
            }
            if (totalChars + content.length() > ATTACHMENT_CONTENT_TOTAL_LIMIT) {
                int remaining = ATTACHMENT_CONTENT_TOTAL_LIMIT - totalChars;
                if (remaining > 0) {
                    appendAttachmentContent(sb, att, content.substring(0, remaining));
                    truncated = true;
                }
                totalExceeded = true;
                break;
            }
            totalChars += content.length();
            appendAttachmentContent(sb, att, content);
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
    private void appendAttachmentContent(StringBuilder sb, Attachment att, String content) {
        String size = att.getFileSize() != null ? att.getFileSize() + " bytes" : "?";
        String type = att.getFileType() != null ? att.getFileType() : "other";
        sb.append("### ").append(att.getFileName())
                .append("（").append(type).append("，").append(size).append("）\n")
                .append(content).append("\n");
    }

    /** 读取可直读附件正文；不可读/为空返回 null（注入"内容不可读"标注，不中断整体注入）。 */
    private String readAttachmentContent(Attachment att) {
        try {
            byte[] bytes = attachmentService.loadContent(att.getId());
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("附件内容读取失败，仅注入清单: attachmentId={}, err={}", att.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 媒体可见性标注：提交含图片/音频/视频附件时，显式告知核验 LLM 当前链路无法查看
     * 原内容、相关文字声称从严核验评分保守。"看不到媒体"与内容注入开关无关，
     * 故开关关闭时同样注入；无媒体附件返回空串。
     */
    private String buildMediaVisibilityNote(Long subTaskId) {
        List<Attachment> attachments = attachmentService.listActive(subTaskId);
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        List<String> mediaNames = attachments.stream()
                .filter(this::isMediaAttachment)
                .map(Attachment::getFileName)
                .toList();
        if (mediaNames.isEmpty()) {
            return "";
        }
        return "本提交含 " + mediaNames.size() + " 个媒体附件（" + String.join("、", mediaNames)
                + "）。当前核验链路无法查看其原始内容；与之相关的文字声称请从严核验、评分保守。\n";
    }

    /**
     * 附件是否文本类（仅文本类注入正文）。优先按 mimeType 判定（text/* 与文本族
     * application 类型）；mimeType 缺失或 octet-stream 时回退扩展名；仍无法判定则
     * fail-close 按非文本处理，宁可不注入正文也不把二进制字节当文本读入 Prompt。
     */
    private boolean isTextualAttachment(Attachment att) {
        String mime = att.getMimeType() != null ? att.getMimeType().toLowerCase() : null;
        if (mime != null && !"application/octet-stream".equals(mime)) {
            if (mime.startsWith("text/")) {
                return true;
            }
            return TEXTUAL_MIME_EXACT.contains(mime);
        }
        return TEXTUAL_EXTENSIONS.contains(extensionOf(att.getFileName()));
    }

    /** 附件是否媒体类（图片/音频/视频）：mimeType 前缀优先，缺失时回退扩展名。 */
    private boolean isMediaAttachment(Attachment att) {
        String mime = att.getMimeType() != null ? att.getMimeType().toLowerCase() : null;
        if (mime != null) {
            for (String prefix : MEDIA_MIME_PREFIXES) {
                if (mime.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return MEDIA_EXTENSIONS.contains(extensionOf(att.getFileName()));
    }

    /** fileName 扩展名小写（不含点）；缺失返回空串。 */
    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase();
    }

    /** 从 context.lastExecution.output 提取执行产出，缺失时给出占位说明。 */
    public String extractExecutionOutput(SubTask subTask) {
        String raw = extractRawOutput(subTask);
        if (!raw.isBlank()) {
            return summarize(raw, OUTPUT_SUMMARY_LIMIT);
        }
        return "（执行产出为空或缺失，请据交付物/验收标准审慎判定）";
    }

    /** 取执行产出原文（不截断），供围栏证据信号检测使用。 */
    public String extractRawOutput(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
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
        boolean hasEvidence = rawOutput != null && rawOutput.contains("VERIFICATION:");
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
