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

    /** 执行产出摘要上限（字符）：超限截断并加省略号，供核验/报告装配摘要口径使用。
     *  <p>2026-10-03 上调：4000 → 64000。核验 Reviewer 走官方 DeepSeek（64K+ 上下文），
     *  原 4000 把执行产出正文砍掉大半，导致「不可见内容不得补全」误判驳回、反复返工；
     *  现按 64K 窗口留足 system/指令/输出余量，执行产出基本全量注入。 */
    public static final int OUTPUT_SUMMARY_LIMIT = 64000;

    /** 附件内容注入限额：每附件 64000 字符，超限截断并标注。
     *  <p>2026-10-03 上调：8000 → 64000。官方 DeepSeek 64K 上下文下，原 8000 是
     *  自我阉割——普通交付物（如 1.4 万字符的整合稿）被从头砍掉后半段，尾部章节
     *  （目录树/教程大纲/启动测试排障正文）对 Reviewer 不可见，直接诱发误判驳回。
     *  现仅对真正的超大文件（&gt;64000 字符）才触发截断。 */
    public static final int ATTACHMENT_CONTENT_PER_FILE_LIMIT = 64000;
    /** 附件内容注入限额：总计 200000 字符，超限停止注入后续附件正文。
     *  <p>2026-10-03 上调：24000 → 200000。留足 64K 上下文下的 system prompt/指令/输出空间。 */
    public static final int ATTACHMENT_CONTENT_TOTAL_LIMIT = 200000;

    /**
     * 会话附件的默认模型窗口（token）：保守取 128K。
     *
     * <p>取值依据：Kimi 官方 FAQ 载明其单轮上下文约 128K token（≈5~6 万汉字）——这是
     * 当前主流商用模型的<b>中位水平</b>，取它作默认可在「不知道实际窗口」时保证不溢出。
     * 平台若换用 1M 窗口模型，应通过 {@code helloai.attachment.context-window-tokens} 上调。</p>
     */
    public static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000;

    /**
     * 附件正文最多占模型窗口的百分比：剩余留给会话历史 / 检索结果 / 输出。
     *
     * <p>40% 是保守分配——历史窗口（{@code HISTORY_WINDOW} 条）+ 联网检索结果（最多 15 条）
     * + 输出（max-tokens）在长会话里能吃掉一半以上。</p>
     */
    public static final int ATTACHMENT_WINDOW_SHARE_PERCENT = 40;

    /** 保守换算：1 个汉字 ≈ 2 token（Kimi 官方 FAQ：128K token ≈ 5~6 万汉字）。 */
    public static final int TOKENS_PER_CHAR = 2;

    /** 会话附件正文预算下限（字符）：窗口极小时兜底，避免预算归零导致完全不可用。 */
    public static final int CHAT_ATTACHMENT_MIN_TOTAL_LIMIT = 4_000;

    /**
     * 会话附件正文总预算（`ATT-7`）：按模型上下文窗口换算，且不超过 G-016 的共享上限。
     *
     * <p><b>为什么不改 {@link #ATTACHMENT_CONTENT_TOTAL_LIMIT} 本身</b>：那个常量被报告链
     * （{@code TaskFinalReportServiceImpl} / {@code ReviewEvidenceAssembler}）与会话附件链
     * 共用，报告链的窗口假设不同（核验走 64K+ 上下文的官方模型），改它会连带改报告口径。</p>
     *
     * <p><b>为什么按「字」不按「token」</b>：既有注入/截断逻辑、DB 列 {@code extracted_chars}、
     * 前端截断提示全部以字符为单位；换成 token 要打通三处。这里用保守系数（1 字 ≈ 2 token）
     * 把窗口折算成字符预算，等价达成目的。</p>
     *
     * @param contextWindowTokens 模型上下文窗口（token）；≤0 时按 {@link #DEFAULT_CONTEXT_WINDOW_TOKENS}
     * @return 本次会话可注入的附件正文总字符数
     */
    public static int resolveChatAttachmentTotalLimit(int contextWindowTokens) {
        int window = contextWindowTokens > 0 ? contextWindowTokens : DEFAULT_CONTEXT_WINDOW_TOKENS;
        long byWindow = (long) window * ATTACHMENT_WINDOW_SHARE_PERCENT / 100 / TOKENS_PER_CHAR;
        int capped = (int) Math.min(byWindow, ATTACHMENT_CONTENT_TOTAL_LIMIT);
        return Math.max(CHAT_ATTACHMENT_MIN_TOTAL_LIMIT, capped);
    }

    /**
     * 会话附件**检索片段**占模型窗口的百分比（`ATT-3`）：15%。
     *
     * <p>与正文注入的 40% 分开算：正文是「已经拿到的固定部分」，检索片段是「按提问临时召回的
     * 补充」。两者相加会超过 40% 的意图，所以检索单独占一块，不挤正文。</p>
     */
    public static final int ATTACHMENT_RETRIEVAL_WINDOW_SHARE_PERCENT = 15;

    /** 检索片段注入预算下限（字符）：窗口极小时兜底。 */
    public static final int CHAT_ATTACHMENT_MIN_RETRIEVAL_LIMIT = 2_000;

    /**
     * 会话附件检索片段的注入预算（`ATT-3`）：按模型窗口的 15% 折算。
     *
     * <p><b>为什么不能复用 {@code KbProperties.charBudget}</b>：那是全库检索
     * （{@code search_knowledge} 工具 / 管理端）的全局预算，默认仅 4,000 字。
     * 会话附件动辄几万字，4,000 字只能装 6~7 个 600 字分片——按 90,385 字的文档算，
     * 检索覆盖率仅 3.3%，问「文档后半部分的某个主题」基本答不出。两处预算的
     * 需求差一个量级，必须分开。</p>
     *
     * <p>128K 窗口 ⇒ 128000 × 15% ÷ 2 = **9,600 字**（约 16 个分片）。</p>
     */
    public static int resolveChatAttachmentRetrievalLimit(int contextWindowTokens) {
        int window = contextWindowTokens > 0 ? contextWindowTokens : DEFAULT_CONTEXT_WINDOW_TOKENS;
        long byWindow = (long) window * ATTACHMENT_RETRIEVAL_WINDOW_SHARE_PERCENT / 100 / TOKENS_PER_CHAR;
        return (int) Math.max(CHAT_ATTACHMENT_MIN_RETRIEVAL_LIMIT,
                Math.min(byWindow, ATTACHMENT_CONTENT_TOTAL_LIMIT));
    }

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