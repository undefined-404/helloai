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
 * EVIDENCE: [{"kind":"sandbox_exec","callId":"c17"}]
 * </pre>
 *
 * <p><b>`SL-5` 起以引用式为主</b>：{@code callId} 是平台台账条目的坐标（`SL-4` 生成），
 * 模型只需抄 ID，不再手抄命令与摘要——这正是 `SR-5` 遗留 mismatch 的根因（省略式命令与
 * 200 字符预览永远比不齐）。旧四字段格式
 * （{@code kind + command + exitCode + truncated + digest}）保留一版兼容：真机上已发出的围栏
 * 模板、历史日志与旧 Prompt 不会因升级立刻失效。</p>
 *
 * <p><b>判定口径</b>：解析失败一律视为<b>证据缺失</b>，不外推、不猜测——
 * 这是本计划要消灭的「自述」：模型说执行过不等于执行过，证据块不可解析就没有证据。
 * 只要有一项结构不合契约（非 JSON 数组 / 元素非对象 / 缺键 / 类型不符），整块判
 * {@link Status#MALFORMED}（不做「部分采信」，避免带病证据进入对账）。</p>
 *
 * <p><b>digest 的取值边界</b>：旧格式下键必须存在，但值允许为空串（模型无法稳定复算输出摘要时，
 * 由对账方降级为「command + exitCode + truncated」三项比对）。引用式下旧字段全部可选；
 * 一旦提供就必须类型合法且值非空，否则整块判 {@code MALFORMED}（防「字段写了但写坏」的带病证据）。</p>
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
     * @param callId    引用的台账条目 ID（`SL-5` 引用式；旧格式为 {@code null} / 空）
     * @param command   自报执行过的命令（引用式下可选；旧格式必填）
     * @param exitCode  自报退出码（引用式下可选；旧格式必填）
     * @param truncated 自报输出是否被截断（引用式下可选；旧格式必填）
     * @param digest    自报输出摘要（{@code sha256:<hex>}；旧格式键必存在但值可为空串）
     */
    public record Evidence(String kind, String callId, String command, Integer exitCode,
                           Boolean truncated, String digest) {

        /** 是否引用式（携带非空 {@code callId}）。 */
        public boolean hasCallId() {
            return callId != null && !callId.isBlank();
        }
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
        if (kind == null || !kind.isTextual() || kind.asText().isBlank()) {
            return null;
        }
        JsonNode callIdNode = node.get("callId");
        if (callIdNode != null && !callIdNode.isTextual()) {
            return null;
        }
        String callId = callIdNode == null ? "" : callIdNode.asText().trim();

        JsonNode command = node.get("command");
        JsonNode exitCode = node.get("exitCode");
        JsonNode truncated = node.get("truncated");
        JsonNode digest = node.get("digest");

        // 引用式（`SL-5`）：callId 即坐标；旧字段可选，但出现即须合法（不做部分采信）
        if (!callId.isEmpty()) {
            String commandText = null;
            if (command != null) {
                if (!command.isTextual()) {
                    return null;
                }
                commandText = blankToNull(command.asText().trim());
            }
            Integer exitCodeValue = null;
            if (exitCode != null) {
                if (!exitCode.isIntegralNumber() || !exitCode.canConvertToInt()) {
                    return null;
                }
                exitCodeValue = exitCode.asInt();
            }
            Boolean truncatedValue = null;
            if (truncated != null) {
                if (!truncated.isBoolean()) {
                    return null;
                }
                truncatedValue = truncated.asBoolean();
            }
            String digestText = null;
            if (digest != null) {
                if (!digest.isTextual()) {
                    return null;
                }
                digestText = digest.asText().trim();
            }
            return new Evidence(kind.asText().trim(), callId, commandText, exitCodeValue,
                    truncatedValue, digestText);
        }

        // 旧格式（兼容一版）：四个字段缺一不可
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
        return new Evidence(kind.asText().trim(), null, command.asText().trim(), exitCode.asInt(),
                truncated.asBoolean(), digest.asText().trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
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
