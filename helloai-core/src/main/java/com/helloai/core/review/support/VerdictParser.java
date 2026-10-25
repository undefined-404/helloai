package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.shared.util.LlmJsonSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动核验判定解析器：把核验 LLM 输出解析为结构化判定 + 渲染/摘要工具。
 *
 * <p>职责边界（自 {@code SubTaskReviewServiceImpl} 拆分）：</p>
 * <ul>
 *     <li>判定解析 {@link #parseVerdict(String)}：LLM 输出 → {@link SubTaskReviewService.ReviewVerdict}，
 *         容错 markdown fence 剥离 + 未转义反斜杠修复；不可解析返回 null
 *         （调用方据此停留 REVIEW 等人工兜底）；</li>
 *     <li>判定渲染与工具：{@link #formatReviewResult}（前端可读中文结论）、
 *         {@link #summarize}（长文本摘要）、{@link #safeMap}（键值对安全组装）、
 *         {@link #nullToEmpty}（空值兜底）、
 *         {@link #normalizeMissingEvidence}（P2-4 缺失证据清单防御归一）。</li>
 * </ul>
 *
 * <p>纯解析无状态：不触发任何状态变更，只做字符串 ↔ 结构化对象转换。</p>
 */
@Component
public class VerdictParser {

    /** 缺失证据清单条目上限（防 LLM 超量输出把下游注入文本撑爆）。 */
    private static final int MAX_MISSING_EVIDENCE = 20;

    private final ObjectMapper objectMapper;

    /** 显式全参构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在 IDE 增量编译里漏抓新增
     * final 字段的坑：显式列为 Spring DI 唯一依据）。 */
    @Autowired
    public VerdictParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析核验判定 JSON；严格解析不可用时走 {@link #looseVerdict} 宽松兜底；取不到 {@code pass}
     * 才返回 null（调用方据此停留 REVIEW / 记录不可解析）。
     */
    public SubTaskReviewService.ReviewVerdict parseVerdict(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        String cleaned = LlmJsonSanitizer.fixInvalidEscapes(stripToJsonObject(rawOutput));
        try {
            SubTaskReviewService.ReviewVerdict verdict =
                    objectMapper.readValue(cleaned, SubTaskReviewService.ReviewVerdict.class);
            if (verdict != null && verdict.getPass() != null) {
                return verdict;
            }
        } catch (Exception e) {
            // 落到下面的宽松兜底（非静默丢弃：兜底仍取不到 pass 才判不可解析）
        }
        return looseVerdict(rawOutput);
    }

    /**
     * 宽松兜底判定（2026-10-16 事故修）：严格 JSON 解析失败时，仍从原文提取<b>明确可见</b>的
     * {@code pass}（必需）/ {@code score} / {@code issues}。
     *
     * <p><b>为什么需要</b>：审查模型已经写出 {@code "pass": false, "score": 2}，却因 JSON 存在格式
     * 瑕疵（未转义字符 / 尾段被截 / 收尾轮追加内容）而整条判定被判「不可解析」——报告审查侧
     * 对不可解析的处置是 {@code convergeToDone}（直接放过），等价于<b>把明确的驳回静默改成通过</b>
     * （实测事故：task 2110984337513603074 报告正文全缺、审查 {@code pass=false} 却被丢弃）。
     * 判定解析的职责是「读懂判定」，不是「要求模型输出完美 JSON」。</p>
     *
     * <p><b>边界</b>：只在严格解析不可用时启用；{@code pass} 取不到即返回 null（不猜）。</p>
     */
    private static SubTaskReviewService.ReviewVerdict looseVerdict(String raw) {
        Matcher passMatcher = PASS_PATTERN.matcher(raw);
        if (!passMatcher.find()) {
            return null;
        }
        SubTaskReviewService.ReviewVerdict verdict = new SubTaskReviewService.ReviewVerdict();
        verdict.setPass(Boolean.parseBoolean(passMatcher.group(1)));
        Matcher scoreMatcher = SCORE_PATTERN.matcher(raw);
        if (scoreMatcher.find()) {
            try {
                verdict.setScore(Integer.parseInt(scoreMatcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 超范围数字：放弃 score，不放弃判定
            }
        }
        Matcher issuesMatcher = ISSUES_PATTERN.matcher(raw);
        if (issuesMatcher.find()) {
            verdict.setIssues(issuesMatcher.group(1).trim());
        }
        return verdict;
    }

    /** 宽松兜底：{@code "pass": true|false}（判定必需项）。 */
    private static final Pattern PASS_PATTERN =
            Pattern.compile("\"pass\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    /** 宽松兜底：{@code "score": 0-999}（可选）。 */
    private static final Pattern SCORE_PATTERN = Pattern.compile("\"score\"\\s*:\\s*(\\d{1,3})");
    /** 宽松兜底：{@code "issues": "…"} 首个引号前的内容（可选；含转义引号时提前截断，可接受）。 */
    private static final Pattern ISSUES_PATTERN = Pattern.compile("\"issues\"\\s*:\\s*\"([^\"]{0,2000})");

    /** 剥离 markdown 代码块围栏，并兜底截取首尾花括号之间的 JSON 对象。 */
    private static String stripToJsonObject(String raw) {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) {
                cleaned = cleaned.substring(0, fenceEnd);
            }
            cleaned = cleaned.trim();
        }
        if (!cleaned.startsWith("{")) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
        }
        return cleaned;
    }

    /** 把 ReviewVerdict 渲染为前端可直接阅读的中文结论。 */
    public static String formatReviewResult(SubTaskReviewService.ReviewVerdict verdict) {
        if (verdict == null) {
            return "核验结论缺失";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 核验结论\n\n");
        sb.append("- 结果: ").append(Boolean.TRUE.equals(verdict.getPass()) ? "通过" : "驳回").append("\n");
        if (verdict.getScore() != null) {
            sb.append("- 评分: ").append(verdict.getScore()).append(" / 5\n");
        }
        if (verdict.getIssues() != null && !verdict.getIssues().isBlank()) {
            sb.append("- 问题: ").append(verdict.getIssues()).append("\n");
        }
        if (verdict.getComment() != null && !verdict.getComment().isBlank()) {
            sb.append("- 评语: ").append(verdict.getComment()).append("\n");
        }
        // TD-5 验收③（2026-10-24）：逐条对照验收标准的核验分析（REVIEWED kind 子任务
        // 「逐条 pass/fail 可见」的对话流主通道）——仅增强展示，不参与判定
        if (verdict.getAnalysis() != null && !verdict.getAnalysis().isBlank()) {
            sb.append("- 逐条核验: ").append(verdict.getAnalysis()).append("\n");
        }
        return sb.toString().trim();
    }

    /** 长文本摘要：超限截断并加省略号。 */
    public static String summarize(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "...";
    }

    /** 键值对安全组装 Map：奇数/非 String 键自动跳过（timeline payload 防 NPE）。 */
    public static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i] instanceof String key) {
                result.put(key, keyValues[i + 1]);
            }
        }
        return result;
    }

    /** 空值兜底：null → 空串（Prompt 占位替换防 NPE）。 */
    public static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /**
     * 归一核验输出的 missingEvidence（P2-4 轻方案）：缺失/非数组/空数组 → 空清单；
     * 非对象元素、三键全空条目一律丢弃；文本键去首尾空白后保留；数值/布尔容忍 toString。
     * 任意非法形态零影响（纯防御承接，不改变 pass 判定与状态流转）。
     */
    public static List<Map<String, String>> normalizeMissingEvidence(JsonNode raw) {
        if (raw == null || !raw.isArray() || raw.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> normalized = new ArrayList<>();
        for (JsonNode node : raw) {
            if (normalized.size() >= MAX_MISSING_EVIDENCE) {
                break;
            }
            if (node == null || !node.isObject()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            putIfText(item, "acceptanceRef", node.get("acceptanceRef"));
            putIfText(item, "missing", node.get("missing"));
            putIfText(item, "howTo", node.get("howTo"));
            if (!item.isEmpty()) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    /** 提取文本值写入目标 Map：空/缺失/容器类型一律跳过（防御式，不抛异常）。 */
    private static void putIfText(Map<String, String> target, String key, JsonNode node) {
        if (node == null || node.isNull() || node.isObject() || node.isArray()) {
            return;
        }
        String text = node.asText();
        if (text != null && !text.isBlank()) {
            target.put(key, text.trim());
        }
    }
}
