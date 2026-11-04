package com.helloai.core.agent.runtime.loop.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 自审证据块解析器（`SR-2`，纯静态、零 Spring 依赖）。
 *
 * <p><b>围栏契约</b>（与 {@code FinalReportFenceHook} 的两条收尾指令同源）：</p>
 * <pre>
 * EVIDENCE: [{"kind":"sandbox_exec","command":"...","exitCode":0,"truncated":false,"digest":"sha256:..."}]
 * </pre>
 *
 * <p><b>判定口径</b>：解析失败一律视为<b>证据缺失</b>，不外推、不猜测——
 * 这是本计划要消灭的「自述」：模型说执行过不等于执行过，证据块不可解析就没有证据。
 * 只要有一项结构不合契约（非 JSON 数组 / 元素非对象 / 缺键 / 类型不符），整块判
 * {@link Status#MALFORMED}（不做「部分采信」，避免带病证据进入对账）。</p>
 *
 * <p><b>digest 的取值边界</b>：键必须存在，但值允许为空串（模型无法稳定复算输出摘要时，
 * 由对账方降级为「command + exitCode + truncated」三项比对）。</p>
 */
public final class SelfReviewEvidenceParser {

    /** 证据段的起始标记（收尾围栏最后一段）。 */
    public static final String MARKER = "EVIDENCE:";

    /** 解析状态。 */
    public enum Status {
        /** 围栏里没有 {@code EVIDENCE:} 段。 */
        ABSENT,
        /** 有标记但内容不合契约（非法 JSON / 非数组 / 缺键 / 类型不符）。 */
        MALFORMED,
        /** 成功解析（entries 可能为空数组）。 */
        PARSED
    }

    /**
     * 单条证据（结构对齐台账台账条目）。
     *
     * @param kind      证据类型（当前唯一约定 {@code sandbox_exec}）
     * @param command   模型自报执行过的命令
     * @param exitCode  自报退出码
     * @param truncated 自报输出是否被截断
     * @param digest    自报输出摘要（{@code sha256:<hex>}；可为空串表示未计算）
     */
    public record Evidence(String kind, String command, int exitCode, boolean truncated, String digest) {
    }

    /**
     * 解析结果。
     *
     * @param status  解析状态
     * @param entries 成功解析的条目（其余状态恒为空清单）
     */
    public record Parsed(Status status, List<Evidence> entries) {

        public Parsed {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }

        /** 是否构成「可用证据」：解析成功且至少一条。 */
        public boolean usable() {
            return status == Status.PARSED && !entries.isEmpty();
        }

        static Parsed absent() {
            return new Parsed(Status.ABSENT, List.of());
        }

        static Parsed malformed() {
            return new Parsed(Status.MALFORMED, List.of());
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SelfReviewEvidenceParser() {
    }

    /**
     * 从收尾轮正文里解析证据块。
     *
     * @param text 收尾轮正文（可空）
     * @return 解析结果（永不为 null、永不抛异常）
     */
    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            return Parsed.absent();
        }
        int idx = text.lastIndexOf(MARKER);
        if (idx < 0) {
            return Parsed.absent();
        }
        String candidate = text.substring(idx + MARKER.length()).trim();
        candidate = stripCodeFence(candidate);
        int start = candidate.indexOf('[');
        int end = candidate.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return Parsed.malformed();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(candidate.substring(start, end + 1));
        } catch (Exception e) {
            return Parsed.malformed();
        }
        if (root == null || !root.isArray()) {
            return Parsed.malformed();
        }
        List<Evidence> entries = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            Evidence evidence = toEvidence(node);
            if (evidence == null) {
                return Parsed.malformed();
            }
            entries.add(evidence);
        }
        return new Parsed(Status.PARSED, entries);
    }

    /** 单条结构校验：任何缺键 / 类型不符都返回 null（由调用方整块判 MALFORMED）。 */
    private static Evidence toEvidence(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode kind = node.get("kind");
        JsonNode command = node.get("command");
        JsonNode exitCode = node.get("exitCode");
        JsonNode truncated = node.get("truncated");
        JsonNode digest = node.get("digest");
        if (kind == null || !kind.isTextual() || kind.asText().isBlank()) {
            return null;
        }
        if (command == null || !command.isTextual() || command.asText().isBlank()) {
            return null;
        }
        if (exitCode == null || !exitCode.canConvertToInt() || !exitCode.isIntegralNumber()) {
            return null;
        }
        if (truncated == null || !truncated.isBoolean()) {
            return null;
        }
        if (digest == null || !digest.isTextual()) {
            return null;
        }
        return new Evidence(kind.asText().trim(), command.asText().trim(), exitCode.asInt(),
                truncated.asBoolean(), digest.asText().trim());
    }

    /** 容忍模型把 JSON 包进 ```json 代码块（只剥首尾围栏标记，不改内容）。 */
    private static String stripCodeFence(String text) {
        String value = text;
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            value = firstNewline < 0 ? "" : value.substring(firstNewline + 1);
        }
        if (value.endsWith("```")) {
            value = value.substring(0, value.length() - 3);
        }
        return value.trim();
    }
}
