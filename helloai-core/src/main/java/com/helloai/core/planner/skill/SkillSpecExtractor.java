package com.helloai.core.planner.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 从模型的自由输出里抽出「图表类型 + 输入 JSON」（2026-10-27）。
 *
 * <p><b>为什么需要这一层</b>：对话内直接出图走的是「模型产结构化输入 → 平台跑技能 CLI」两段式。
 * 模型不会严格只输出 JSON（常见：先说一句「好的，我来画」，再给 ```json 围栏，末尾再补一段说明），
 * 而 CLI 只吃纯 JSON 文件。抽取层的职责就是把这些噪声剥掉，并在<b>抽不出来时给出可读原因</b>——
 * 直接抛「解析失败」会让排查倒退到猜。</p>
 *
 * <p><b>信封契约</b>：{@code {"chartType":"<type>","spec":{...}}}。选信封而不是「整段就是 spec」，
 * 是因为 CLI 需要 {@code <type>} 作为第一个位置参数，而 spec 内部<b>没有</b>可靠的类型字段——
 * 靠猜会画出结构不对的图（比失败更糟：用户看到一张错的图，还以为平台理解对了）。</p>
 *
 * <p><b>只认 10 种已知类型</b>：{@code archify} 的类型是封闭集合（见技能正文的图表类型表）。
 * 让模型自创新类型名只会换来一次 {@code validate} 失败，不如在入口判掉。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillSpecExtractor {

    /** {@code archify} 支持的图表类型（与技能正文的类型表逐字一致）。 */
    public static final Set<String> CHART_TYPES = Set.of(
            "architecture", "workflow", "sequence", "dataflow", "lifecycle",
            "erd", "tree", "class", "timeline", "waterfall");

    /** 抽取失败的载体：message 直接面向用户/日志，不用堆栈。 */
    public static class SpecExtractException extends RuntimeException {

        public SpecExtractException(String message) {
            super(message);
        }
    }

    /**
     * 抽取结果。
     *
     * @param chartType 图表类型（已校验在 {@link #CHART_TYPES} 内）
     * @param specJson  纯 JSON 文本（可直接落盘交给 CLI）
     */
    public record ChartSpec(String chartType, String specJson) {
    }

    private final ObjectMapper objectMapper;

    /**
     * 抽取。
     *
     * @param raw 模型输出原文（可含前后说明、代码围栏）
     * @return 图表类型 + spec JSON
     * @throws SpecExtractException 抽不出信封 / 类型非法 / spec 非对象
     */
    public ChartSpec extract(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new SpecExtractException("模型没有返回任何内容");
        }
        String json = extractJsonBlock(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new SpecExtractException("模型输出里找不到合法的 JSON 对象：" + brief(e.getMessage()));
        }
        String chartType = textOf(root, "chartType");
        if (chartType == null) {
            chartType = textOf(root, "chart_type");
        }
        if (chartType == null) {
            throw new SpecExtractException("模型输出缺少 chartType 字段（应为 "
                    + CHART_TYPES + " 之一）");
        }
        String normalized = chartType.trim().toLowerCase();
        if (!CHART_TYPES.contains(normalized)) {
            throw new SpecExtractException("模型给出的图表类型不受支持：" + chartType
                    + "（应为 " + CHART_TYPES + " 之一）");
        }
        JsonNode spec = root.get("spec");
        if (spec == null) {
            spec = root.get("data");
        }
        if (spec == null || !spec.isObject()) {
            throw new SpecExtractException("模型输出缺少 spec 对象（图表输入必须是一个 JSON 对象）");
        }
        try {
            return new ChartSpec(normalized, objectMapper.writeValueAsString(spec));
        } catch (Exception e) {
            throw new SpecExtractException("spec 序列化失败：" + brief(e.getMessage()));
        }
    }

    /**
     * 从自由文本里取「最像 JSON 的那段」：优先 ```json 围栏，其次第一个能配平的 {@code {...}}。
     *
     * <p>不用「找第一个 { 到最后一个 }」：模型常在 JSON 之后写说明，说明里可能带括号；
     * 用花括号配对（跳过字符串内的括号）才能截在正确位置。</p>
     */
    private String extractJsonBlock(String raw) {
        int fenceStart = raw.indexOf("```");
        while (fenceStart >= 0) {
            int lineEnd = raw.indexOf('\n', fenceStart);
            if (lineEnd < 0) {
                break;
            }
            int fenceEnd = raw.indexOf("```", lineEnd);
            if (fenceEnd < 0) {
                break;
            }
            String body = raw.substring(lineEnd + 1, fenceEnd).trim();
            if (body.startsWith("{")) {
                return body;
            }
            fenceStart = raw.indexOf("```", fenceEnd + 3);
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            throw new SpecExtractException("模型输出里没有 JSON 对象");
        }
        int end = matchBrace(raw, start);
        if (end < 0) {
            throw new SpecExtractException("模型输出的 JSON 括号不配对（可能被截断）");
        }
        return raw.substring(start, end + 1);
    }

    /** 返回与 {@code start} 处 '{' 配对的 '}' 下标；不配对返回 -1。字符串内与转义忽略。 */
    private static int matchBrace(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String textOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || !node.isTextual() || node.asText().isBlank() ? null : node.asText();
    }

    private static String brief(String message) {
        if (message == null) {
            return "";
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
    }
}
