package com.helloai.core.planner.attachment;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 会话附件 → Prompt 文本（方案 §3.4 三层注入的前两层）。
 *
 * <p><b>MANIFEST（恒注入）</b>：文件清单。哪怕正文一个字都读不出，也要让模型知道
 * 「用户给了哪些文件、哪个读不了」——否则它会把「没有正文」误当成「用户没提附件」。</p>
 *
 * <p><b>CONTEXT（按预算注入）</b>：可读正文。预算直接复用 G-016 的
 * {@link AttachmentContentPolicy}（单文件 64k / 总计 200k），不另立一套数字——
 * 同一个「附件正文能占多少上下文」的问题，全仓只能有一个答案。</p>
 *
 * <p>第三层（大文件 RAG 检索）在 P1，这里不做；超预算的文件在 CONTEXT 里显式标注
 * 「已截断/未注入」，让模型知道信息不全，而不是以为全文就这么多。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAttachmentPromptRenderer {

    /** 无附件时的占位：保持 Prompt 语义节稳定（与联网资料节的「（无可用联网资料）」同风格）。 */
    private static final String NO_ATTACHMENT = "（本轮无附件）";

    private final ChatAttachmentService chatAttachmentService;

    /**
     * 渲染结果。
     *
     * @param manifest 清单表（恒非空，无附件时为占位文本）
     * @param context  正文层（无附件或全部不可读时为说明文本）
     */
    public record Rendered(String manifest, String context) {
    }

    /** 一次查询渲染两层（避免同一轮 Prompt 渲染查两遍库）。 */
    public Rendered render(Long conversationId) {
        List<ChatAttachment> attachments = chatAttachmentService.listByConversation(conversationId);
        if (attachments == null || attachments.isEmpty()) {
            return new Rendered(NO_ATTACHMENT, NO_ATTACHMENT);
        }
        return new Rendered(buildManifest(attachments), buildContext(attachments));
    }

    /** 清单：序号 / 文件名 / 类型 / 大小 / 页数 / 状态 / 正文字数。 */
    private String buildManifest(List<ChatAttachment> attachments) {
        StringBuilder sb = new StringBuilder();
        sb.append("| 序号 | 文件名 | 类型 | 大小 | 页数 | 状态 | 正文字数 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < attachments.size(); i++) {
            ChatAttachment a = attachments.get(i);
            sb.append("| 附件").append(i + 1)
                    .append(" | ").append(safe(a.getFileName()))
                    .append(" | ").append(safe(a.getFileType()))
                    .append(" | ").append(sizeText(a.getFileSize()))
                    .append(" | ").append(a.getPageCount() == null ? "-" : a.getPageCount())
                    .append(" | ").append(statusText(a))
                    .append(" | ").append(a.getExtractedChars() == null ? "-" : a.getExtractedChars())
                    .append(" |\n");
        }
        return sb.toString().trim();
    }

    /** 正文：按预算逐份注入，超预算显式标注。 */
    private String buildContext(List<ChatAttachment> attachments) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < attachments.size(); i++) {
            ChatAttachment a = attachments.get(i);
            String header = "### [附件" + (i + 1) + "] " + safe(a.getFileName()) + "（" + statusText(a) + "）";
            String text = a.getExtractedText();
            if (a.getParseStatus() != ChatAttachmentParseStatus.READY || text == null || text.isBlank()) {
                sb.append(header).append('\n')
                        .append("（无可用正文")
                        .append(a.getParseError() == null || a.getParseError().isBlank()
                                ? "" : "：" + a.getParseError())
                        .append("）\n\n");
                continue;
            }
            int remaining = AttachmentContentPolicy.ATTACHMENT_CONTENT_TOTAL_LIMIT - used;
            if (remaining <= 0) {
                sb.append(header).append('\n')
                        .append("（本次未注入正文：上下文预算已用尽，如需该文件内容请让用户缩小范围）\n\n");
                continue;
            }
            int quota = Math.min(AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT, remaining);
            boolean truncated = text.length() > quota;
            String body = truncated ? text.substring(0, quota) : text;
            used += body.length();
            sb.append(header).append('\n')
                    .append(body).append('\n')
                    .append(truncated
                            ? "（该文件正文已截断：原文 " + text.length() + " 字，本次仅注入前 " + quota + " 字）\n\n"
                            : "\n");
            log.debug("附件正文注入: fileName={}, chars={}, truncated={}", a.getFileName(), body.length(), truncated);
        }
        return sb.toString().trim();
    }

    /** 状态词：面向模型，不用枚举名（PENDING/PARSING 对模型是噪音）。 */
    private static String statusText(ChatAttachment a) {
        ChatAttachmentParseStatus status = a.getParseStatus();
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case READY -> "可读";
            case PENDING, PARSING -> "解析中（本轮可能还读不到正文）";
            case FAILED -> "解析失败";
            case SKIPPED -> "需识别（无正文）";
        };
    }

    private static String sizeText(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0fKB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0);
    }

    private static String safe(String text) {
        return text == null || text.isBlank() ? "-" : text;
    }
}
