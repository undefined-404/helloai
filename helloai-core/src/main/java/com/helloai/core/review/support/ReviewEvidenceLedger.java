package com.helloai.core.review.support;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据注入台账（`RV-8`，纯函数、零 Spring 依赖）。
 *
 * <p>核验 Prompt 里的附件正文是被预算裁剪过的；台账把「哪些附件注入了多少、哪些不可读、
 * 哪些是二进制」变成结构化事实，并随判定快照冻结。它由 {@link ReviewEvidenceAssembler}
 * 在**分配预算的同一次循环**里产出，不允许在别处重算——否则台账与正文会各说各话。</p>
 *
 * <p><b>格式 v1</b>（紧凑、可机器解析、确定性）：</p>
 * <pre>
 * [EVIDENCE_LEDGER] v1 totalChars=200000 entries=35 omitted=0
 * entry id=2119683891861348354 name=detail.csv chars=155000 status=truncated range=head+tail shown=8192
 * entry id=2119683891861348355 name=report.md chars=9000 status=injected range=full shown=9000
 * entry id=2119683891861348356 name=broken.csv chars=0 status=absent reason=unreadable
 * entry id=2119683891861348357 name=chart.png chars=0 status=not_textual
 * </pre>
 */
public final class ReviewEvidenceLedger {

    /** 台账行上限（防 50+ 附件把证据段撑大）。 */
    public static final int MAX_ENTRIES = 60;

    public static final String STATUS_INJECTED = "injected";
    public static final String STATUS_TRUNCATED = "truncated";
    public static final String STATUS_ABSENT = "absent";
    public static final String STATUS_NOT_TEXTUAL = "not_textual";

    public static final String RANGE_FULL = "full";
    public static final String RANGE_HEAD = "head";
    public static final String RANGE_HEAD_TAIL = "head+tail";

    private static final Pattern OMITTED = Pattern.compile("omitted=(\\d+)");

    private static final Pattern ENTRY = Pattern.compile(
            "entry id=(\\d+) name=([^\\s]*) chars=(\\d+) status=([a-z_]+)"
                    + "(?: range=([^\\s]+))?(?: shown=(\\d+))?(?: reason=([^\\s]+))?");

    private ReviewEvidenceLedger() {
    }

    /**
     * 单条台账。
     *
     * @param id     附件主键
     * @param name   文件名（空白转 {@code _}，避免破坏行格式）
     * @param chars  原文总字符数
     * @param status 注入状态（四值见常量）
     * @param range  注入区间；无正文注入时为空串
     * @param shown  实际注入字符数；无正文注入时为 0
     * @param reason 不可读原因；仅 {@code absent} 有值
     */
    public record Entry(Long id, String name, int chars, String status, String range, int shown, String reason) {

        public Entry {
            name = name == null || name.isBlank() ? "unknown" : name.trim().replaceAll("\\s+", "_");
            status = status == null ? "" : status;
            range = range == null ? "" : range;
            reason = reason == null ? "" : reason;
        }

        public boolean truncated() {
            return STATUS_TRUNCATED.equals(status);
        }

        public boolean absent() {
            return STATUS_ABSENT.equals(status);
        }
    }

    /** 渲染台账文本；空清单也输出头行（让 reviewer 知道「平台没读到任何附件正文」）。 */
    public static String render(List<Entry> entries, int totalChars) {
        List<Entry> safe = entries == null ? List.of() : entries;
        int omitted = Math.max(0, safe.size() - MAX_ENTRIES);
        StringBuilder sb = new StringBuilder();
        sb.append("[EVIDENCE_LEDGER] v1 totalChars=").append(Math.max(0, totalChars))
                .append(" entries=").append(safe.size())
                .append(" omitted=").append(omitted).append('\n');
        int limit = Math.min(safe.size(), MAX_ENTRIES);
        for (int i = 0; i < limit; i++) {
            Entry entry = safe.get(i);
            if (entry == null) {
                continue;
            }
            sb.append("entry id=").append(entry.id() == null ? "-" : entry.id())
                    .append(" name=").append(entry.name())
                    .append(" chars=").append(entry.chars())
                    .append(" status=").append(entry.status());
            if (!entry.range().isEmpty()) {
                sb.append(" range=").append(entry.range());
            }
            if (entry.shown() > 0) {
                sb.append(" shown=").append(entry.shown());
            }
            if (!entry.reason().isEmpty()) {
                sb.append(" reason=").append(entry.reason());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 解析台账文本（供复判/断言使用；畸形行跳过，不抛异常）。 */
    public static List<Entry> parse(String ledger) {
        if (ledger == null || ledger.isEmpty()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : ledger.split("\n")) {
            Matcher matcher = ENTRY.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            Long id;
            try {
                id = Long.valueOf(matcher.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            entries.add(new Entry(id, matcher.group(2),
                    parseInt(matcher.group(3)), matcher.group(4),
                    matcher.group(5), parseInt(matcher.group(6)), matcher.group(7)));
        }
        return List.copyOf(entries);
    }

    /** 查找指定附件的台账条目；无则返回 {@code null}。 */
    public static Entry find(String ledger, Long attachmentId) {
        if (attachmentId == null) {
            return null;
        }
        for (Entry entry : parse(ledger)) {
            if (attachmentId.equals(entry.id())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 头行里的 {@code omitted=k}（被 {@link #MAX_ENTRIES} 截掉、未逐条列出的条目数）；无头行返回 0。
     *
     * <p>用于区分「查不到该 id」的两种成因：台账溢出时被略去的条目确实注入过，模型无从知道
     * 它的状态，此时按平台缺口处理（fail-open）；台账没溢出却查不到，只能是模型写了个不存在的 id。</p>
     */
    public static int omittedCount(String ledger) {
        if (ledger == null || ledger.isEmpty()) {
            return 0;
        }
        Matcher matcher = OMITTED.matcher(ledger);
        return matcher.find() ? parseInt(matcher.group(1)) : 0;
    }

    /** 是否存在任何被截断注入的附件。 */
    public static boolean anyTruncated(String ledger) {
        for (Entry entry : parse(ledger)) {
            if (entry.truncated()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在任何不可读附件。当前无生产消费方（{@link #gap} 的「未写 id」分支已收窄为只认截断，`RV-11`），
     * 仅单测锁 {@code absent} 语义；保留供复判排查「有没有读不出的附件」，长期无人调用则按「无读取方 API = 反模式」下线。
     */
    public static boolean anyAbsent(String ledger) {
        for (Entry entry : parse(ledger)) {
            if (entry.absent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 指定附件是否构成平台侧证据缺口：不可读，或被截断。
     *
     * <p>{@code attachmentId == null} 时只要求注入正文存在截断（`RV-11`：不因没写 id 额外要求 absent）。
     * 查不到该 id 不等于平台缺口：仅「台账整体为空」（开关关/无附件）或「条目溢出 {@link #MAX_ENTRIES}」算缺口；
     * 台账非空无溢出却查不到 ⇒ 模型写了不存在的 id，不算缺口，由 {@link ReviewEvidenceUnavailability#unknownIds} 出 {@code WARN} 留痕。</p>
     */
    public static boolean gap(String ledger, Long attachmentId) {
        if (attachmentId == null) {
            return anyTruncated(ledger);
        }
        List<Entry> entries = parse(ledger);
        if (entries.isEmpty()) {
            // 台账整体为空（注入开关关闭 / 无附件）：模型无从断言 id 真伪 ⇒ 归平台缺口
            return true;
        }
        for (Entry entry : entries) {
            if (attachmentId.equals(entry.id())) {
                return entry.absent() || entry.truncated();
            }
        }
        // 查不到：仅台账溢出（被 MAX_ENTRIES 略去的条目确实注入过）时算缺口，否则是编造 id
        return omittedCount(ledger) > 0;
    }

    private static int parseInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
