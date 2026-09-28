package com.helloai.core.shared.util;

import java.util.List;
import java.util.Set;

/**
 * 附件内容注入口径单一常量源（G-016）：限额常量与文本/媒体族判定。
 *
 * <p>报告链（{@code TaskFinalReportServiceImpl}）与核验链（{@code ReviewEvidenceAssembler}）
 * 共用同一套「附件正文注入限额」与「文本/媒体族判定」，避免两套口径漂移：
 * 限额常量只准引用本类，族判定只准走 {@link #isTextual}/{@link #isMedia}。</p>
 *
 * <p>纯静态工具：无 Spring、无业务实体依赖（判定参数取 mimeType/fileName 原始值），
 * 任意域可安全引用。</p>
 */
public final class AttachmentContentPolicy {

    private AttachmentContentPolicy() {
    }

    /** 执行产出摘要上限（字符）：超限截断并加省略号，供核验/报告装配摘要口径使用。 */
    public static final int OUTPUT_SUMMARY_LIMIT = 4000;

    /** 附件内容注入限额：每附件 8000 字符，超限截断并标注。 */
    public static final int ATTACHMENT_CONTENT_PER_FILE_LIMIT = 8000;
    /** 附件内容注入限额：总计 24000 字符，超限停止注入后续附件正文。 */
    public static final int ATTACHMENT_CONTENT_TOTAL_LIMIT = 24000;

    /** 文本族 MIME 精确值集（text/* 前缀另判）：命中才允许注入附件正文。 */
    public static final Set<String> TEXTUAL_MIME_EXACT = Set.of(
            "application/json", "application/xml", "application/x-yaml", "application/yaml", "application/sql");
    /** 文本扩展名兜底集（mimeType 缺失或 octet-stream 时用）：命中才允许注入附件正文。 */
    public static final Set<String> TEXTUAL_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "log", "json", "xml", "yaml", "yml", "csv", "tsv", "sql",
            "java", "py", "js", "ts", "sh", "ps1", "html", "css", "properties", "ini", "toml");
    /** 媒体类 MIME 前缀：图片/音频/视频。 */
    public static final List<String> MEDIA_MIME_PREFIXES = List.of("image/", "audio/", "video/");
    /** 媒体扩展名兜底集（mimeType 缺失或 octet-stream 时用）。 */
    public static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "mp3", "wav", "m4a", "ogg", "flac",
            "mp4", "avi", "mov", "mkv", "webm");

    /**
     * 附件是否文本类（仅文本类注入正文）。优先按 mimeType 判定（text/* 与文本族
     * application 类型）；mimeType 缺失或 octet-stream 时回退扩展名；仍无法判定则
     * fail-close 按非文本处理，宁可不注入正文也不把二进制字节当文本读入 Prompt。
     *
     * @param mimeType 附件 MIME 类型（原始值，可为 null）
     * @param fileName 附件文件名（原始值，可为 null）
     */
    public static boolean isTextual(String mimeType, String fileName) {
        String mime = mimeType != null ? mimeType.toLowerCase() : null;
        if (mime != null && !"application/octet-stream".equals(mime)) {
            if (mime.startsWith("text/")) {
                return true;
            }
            return TEXTUAL_MIME_EXACT.contains(mime);
        }
        return TEXTUAL_EXTENSIONS.contains(extensionOf(fileName));
    }

    /**
     * 附件是否媒体类（图片/音频/视频）：mimeType 前缀优先，缺失时回退扩展名。
     *
     * @param mimeType 附件 MIME 类型（原始值，可为 null）
     * @param fileName 附件文件名（原始值，可为 null）
     */
    public static boolean isMedia(String mimeType, String fileName) {
        String mime = mimeType != null ? mimeType.toLowerCase() : null;
        if (mime != null) {
            for (String prefix : MEDIA_MIME_PREFIXES) {
                if (mime.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return MEDIA_EXTENSIONS.contains(extensionOf(fileName));
    }

    /** fileName 扩展名小写（不含点）；缺失返回空串。 */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase();
    }
}