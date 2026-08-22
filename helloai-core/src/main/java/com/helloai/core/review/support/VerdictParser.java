package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.core.shared.util.LlmJsonSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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
 *         {@link #nullToEmpty}（空值兜底）。</li>
 * </ul>
 *
 * <p>纯解析无状态：不触发任何状态变更，只做字符串 ↔ 结构化对象转换。</p>
 */
@Component
public class VerdictParser {

    private final ObjectMapper objectMapper;

    /** 显式全参构造器（绕开 Lombok {@code @RequiredArgsConstructor} 在 IDE 增量编译里漏抓新增
     * final 字段的坑：显式列为 Spring DI 唯一依据）。 */
    @Autowired
    public VerdictParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析核验判定 JSON；不可解析返回 null（调用方据此停留 REVIEW）。
     */
    public SubTaskReviewService.ReviewVerdict parseVerdict(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        String cleaned = LlmJsonSanitizer.fixInvalidEscapes(stripToJsonObject(rawOutput));
        try {
            SubTaskReviewService.ReviewVerdict verdict =
                    objectMapper.readValue(cleaned, SubTaskReviewService.ReviewVerdict.class);
            if (verdict == null || verdict.getPass() == null) {
                return null;
            }
            return verdict;
        } catch (Exception e) {
            return null;
        }
    }

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
}
