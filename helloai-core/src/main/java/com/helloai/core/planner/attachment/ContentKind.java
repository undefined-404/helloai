package com.helloai.core.planner.attachment;

import com.helloai.core.shared.util.AttachmentContentPolicy;

import java.util.Set;

/**
 * 附件内容族：决定走哪条解析路径（方案 §3.3）。
 *
 * <p>判定复用 {@link AttachmentContentPolicy}（G-016 单一口径），不另建一套扩展名/MIME 表——
 * 否则「能否注入正文」与「能否解析」会漂移成两套语义。</p>
 *
 * <ul>
 *   <li>{@link #TEXT}：文本族（txt/md/csv/json/代码…）→ 直读 + 判码，不经 Tika 渲染。
 *       用户上传 md/代码就是要原样给模型看，Tika 的两种 handler 都会失真（实测见方案 §3.3）。</li>
 *   <li>{@link #STRUCTURED}：结构化格式（pdf/docx/pptx/xlsx…）→ Tika + {@code ToMarkdownContentHandler}。</li>
 *   <li>{@link #MEDIA}：图片/音视频 → P0 阶段 {@code SKIPPED}，P1 接视觉直读。</li>
 *   <li>{@link #UNSUPPORTED}：不认识 → 拒收（扩展名白名单之外）。</li>
 * </ul>
 *
 * <p>注意：{@code html} 归入 {@link #STRUCTURED}（转 Markdown 比原始标签好读，且 Tika 的
 * Markdown handler 能把表格输出成 {@code |a|b|}）；{@code css} 等仍归 {@link #TEXT}。
 * 这与 {@link AttachmentContentPolicy} 不冲突——那边判的是「能否注入正文」，这边判的是「怎么解析」。</p>
 */
public enum ContentKind {

    TEXT,
    STRUCTURED,
    MEDIA,
    UNSUPPORTED;

    /** 结构化格式 MIME 精确值（pdf / Office 三件套 / OpenDocument / RTF / EPUB / HTML）。 */
    private static final Set<String> STRUCTURED_MIME = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/rtf",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/epub+zip",
            "text/html",
            "application/xhtml+xml");

    /** 结构化格式扩展名（含 MIME 缺失时的兜底）。 */
    private static final Set<String> STRUCTURED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "rtf", "odt", "ods", "odp", "epub",
            "html", "htm", "xhtml");

    /**
     * 按 MIME + 文件名判定内容族。
     *
     * @param mimeType 声明或探测到的 MIME（可为 null）
     * @param fileName 原始文件名（可为 null，用于扩展名兜底）
     */
    public static ContentKind of(String mimeType, String fileName) {
        String mime = mimeType != null ? mimeType.toLowerCase() : null;
        if (mime != null && STRUCTURED_MIME.contains(mime)) {
            return STRUCTURED;
        }
        // 扩展名先于文本族判定：html 同时出现在 AttachmentContentPolicy 的文本扩展名集里，
        // 但「能注入正文」不等于「该按原文注入」，html 要转 Markdown 才好读。
        if (STRUCTURED_EXTENSIONS.contains(AttachmentContentPolicy.extensionOf(fileName))) {
            return STRUCTURED;
        }
        if (AttachmentContentPolicy.isTextual(mimeType, fileName)) {
            return TEXT;
        }
        if (AttachmentContentPolicy.isMedia(mimeType, fileName)) {
            return MEDIA;
        }
        return UNSUPPORTED;
    }
}
