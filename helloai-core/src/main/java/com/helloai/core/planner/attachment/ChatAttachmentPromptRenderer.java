package com.helloai.core.planner.attachment;

import com.helloai.common.constant.ChatAttachmentParseStatus;
import com.helloai.core.planner.entity.ChatAttachment;
import com.helloai.core.planner.service.ChatAttachmentService;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** 决策阶段无附件占位：与正文层同风格，保证决策 Prompt 的语义节长度稳定。 */
    private static final String NO_ATTACHMENT_DECISION = "（本轮无附件）";

    /**
     * 决策阶段每份文件的预览字数上限。
     *
     * <p>决策调用（是否联网 + 搜什么词）只需要模型判断「这是什么文件、大概讲什么」，
     * 不需要全文；注入全文会把轻量决策调用抬成重调用。参考 deer-flow #1647 方案四
     * 的 preview 注入（~500 字符），本处取 500 字。</p>
     */
    private static final int DECISION_PREVIEW_CHARS = 500;

    /**
     * 决策阶段锚点抽取的扫描范围上限（字符）。
     *
     * <p>预览只给头部 500 字，但锚点（法规名/标准号/技术专名）可能出现在文档任意位置，
     * 因此对全文扫描——单次 {@code extractedText} 最大 64k（G-016 单文件上限），
     * 这里再限 2 万字避免超长手册拖慢决策调用。</p>
     */
    private static final int ANCHOR_SCAN_CHARS = 20_000;

    /** 锚点数上限：过多会稀释模型注意力，反而选不准搜索词。 */
    private static final int ANCHOR_LIMIT = 20;

    /** 《…》：法规、标准、规范的正式名称，是最强的外部检索锚点。 */
    private static final Pattern DOC_TITLE_PATTERN = Pattern.compile("《[^》\\n]{2,40}》");

    /** 标准号/文号：GB/T 12345、ISO 9001、MH/T 6012、ISO/IEC 27001。 */
    private static final Pattern STANDARD_NO_PATTERN = Pattern.compile(
            "(?:GB/T|GB|ISO/IEC|ISO|IEC|MH/T|MH|ICAO|ANSI|RFC|EN)\\s?\\d{2,6}(?:[.-]\\d{1,4})*");

    /** 首字母大写的英文技术专名：PowerShell、Windows、OpenID、gRPC（排除句首常见虚词）。 */
    private static final Pattern TECH_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9+#._-]{2,24}");

    /** 英文虚词/句首词黑名单：避免把 The/This/Note 之类当成外部锚点。 */
    private static final Set<String> TECH_STOP_WORDS = Set.of(
            "The", "This", "That", "These", "Those", "And", "Or", "Not", "But", "For", "With",
            "When", "What", "Which", "Where", "How", "Why", "Who", "Use", "Used", "Using",
            "Note", "Notes", "See", "Step", "Steps", "If", "It", "Its", "Is", "Are", "Was",
            "Be", "Can", "May", "Must", "Should", "Will", "You", "Your", "Our", "All", "Any",
            "File", "Files", "Page", "Pages", "Table", "Figure", "Chapter", "Part", "Add",
            "Run", "Set", "Get", "New", "From", "Into", "Then", "Than", "Also", "Only");

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

    /**
     * 决策阶段视图（供「是否联网 + 搜什么词」的联合决策 Prompt 使用）。
     *
     * <p><b>为什么需要独立一层</b>：决策调用发生在正文渲染之前，而 {@link #render} 的两层
     * 只注入正文 Prompt。决策阶段看不到附件时，模型只能拿用户那句问话当检索依据——
     * 「帮我分析一下我上传的附件这个手册方案是否正确」会被改写成「手册 方案 正确性
     * 评估」这类无实体空壳词，检索必然跑偏。同一缺陷在 deer-flow #1647 有完整记录
     * （根因 2「附件内容未注入上下文」/ 根因 4「仅凭文件名判断相关性」）。</p>
     *
     * <p><b>为什么不是全文</b>：决策是轻量调用，注入全文会把成本抬到与正文渲染同档，
     * 也稀释模型注意力。这里只给三样足以支撑决策的信号：清单（有什么文件）、
     * 预览（这份文件讲什么）、锚点（能拿什么词去外部核验）。</p>
     *
     * @param conversationId 会话 id
     * @return 决策视图文本；无附件时为占位文本（不返回空，保持 Prompt 语义节稳定）
     */
    public String renderForDecision(Long conversationId) {
        if (conversationId == null) {
            // 新建会话的首轮决策可能还没有 id：按无附件处理，不查库
            return NO_ATTACHMENT_DECISION;
        }
        List<ChatAttachment> attachments = chatAttachmentService.listByConversation(conversationId);
        if (attachments == null || attachments.isEmpty()) {
            return NO_ATTACHMENT_DECISION;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【附件清单】\n").append(buildManifest(attachments)).append("\n\n");

        sb.append("【内容预览】每份文件前 ").append(DECISION_PREVIEW_CHARS).append(" 字\n");
        for (int i = 0; i < attachments.size(); i++) {
            ChatAttachment a = attachments.get(i);
            sb.append("### [附件").append(i + 1).append("] ").append(safe(a.getFileName()))
                    .append("（").append(statusText(a)).append("）\n");
            String text = a.getExtractedText();
            if (a.getParseStatus() != ChatAttachmentParseStatus.READY || text == null || text.isBlank()) {
                sb.append("（无可用正文）\n");
                continue;
            }
            String preview = text.length() > DECISION_PREVIEW_CHARS
                    ? text.substring(0, DECISION_PREVIEW_CHARS) + "…"
                    : text;
            sb.append(preview.replaceAll("\\s+", " ").trim()).append('\n');
        }

        List<String> anchors = extractAnchors(attachments);
        sb.append("\n【可外部核验的锚点】从附件全文抽取，构造 search_query 时只能用这些实体\n");
        if (anchors.isEmpty()) {
            sb.append("（未发现：本附件内没有法规名/标准号/技术专名等可外部核验的实体，"
                    + "说明手册内容无需联网核验）\n");
        } else {
            for (String anchor : anchors) {
                sb.append("- ").append(anchor).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 从附件全文抽取「可外部核验的实体」：法规名、标准号、英文技术专名。
     *
     * <p>这些是搜索词唯一合法的来源——「手册」「附件」这类指代元词在检索域里是噪音，
     * 而《运输机场使用许可规定》、GB/T 12345、PowerShell 这类专名才能命中真实资料。</p>
     *
     * <p>顺序：先法规名（信号最强），再标准号，最后技术专名；同类按出现顺序，
     * 全局去重后截断到 {@link #ANCHOR_LIMIT}。</p>
     */
    private List<String> extractAnchors(List<ChatAttachment> attachments) {
        Set<String> docTitles = new LinkedHashSet<>();
        Set<String> standardNos = new LinkedHashSet<>();
        Set<String> techNames = new LinkedHashSet<>();
        int scanned = 0;
        for (ChatAttachment a : attachments) {
            String text = a.getExtractedText();
            if (a.getParseStatus() != ChatAttachmentParseStatus.READY || text == null || text.isBlank()) {
                continue;
            }
            if (scanned >= ANCHOR_SCAN_CHARS) {
                break;
            }
            int room = Math.min(text.length(), ANCHOR_SCAN_CHARS - scanned);
            String scope = text.substring(0, room);
            scanned += room;

            collect(DOC_TITLE_PATTERN.matcher(scope), docTitles);
            collect(STANDARD_NO_PATTERN.matcher(scope), standardNos);

            Matcher tech = TECH_NAME_PATTERN.matcher(scope);
            while (tech.find()) {
                String name = tech.group();
                if (!TECH_STOP_WORDS.contains(name)) {
                    techNames.add(name);
                }
            }
        }
        List<String> out = new ArrayList<>();
        appendLimited(out, docTitles);
        appendLimited(out, standardNos);
        appendLimited(out, techNames);
        return out;
    }

    /** 把匹配结果按出现顺序并入集合（去掉两侧空白与空项）。 */
    private static void collect(Matcher matcher, Set<String> target) {
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (!value.isBlank()) {
                target.add(value);
            }
        }
    }

    /** 依次追加去重后的锚点，达到上限即停。 */
    private static void appendLimited(List<String> out, Set<String> candidates) {
        for (String candidate : candidates) {
            if (out.size() >= ANCHOR_LIMIT) {
                return;
            }
            if (!out.contains(candidate)) {
                out.add(candidate);
            }
        }
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
