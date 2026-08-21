package com.helloai.core.agent.quality;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 核验 issues 四元组 [defect] 标签解析器（反馈回路第 1 层）。
 *
 * <p>issues 四元组格式（subtask-review.md 约定）：
 * {@code [defect] 缺陷描述 [location] 位置 [impact] 影响 [evidence] 依据}，
 * 多条 issue 以换行分隔。本类提取每条 issue 的 [defect] 段文本作为缺陷标签，
 * 归一化（空白折叠 + 30 字符截断）后计数。</p>
 *
 * <p>纯函数设计：无 IO、无状态，供 QualityProfileUpdater 增量与
 * AgentQualityProfileService.rebuild 重算共用同一口径。</p>
 */
public final class DefectLabelParser {

    /** 标签长度上限：过长的自由文本缺陷描述截断归一，避免标签膨胀。 */
    private static final int LABEL_MAX_LENGTH = 30;

    /** [defect] 段提取：非贪婪匹配到下一个 '[' 或行尾。 */
    private static final Pattern DEFECT_PATTERN = Pattern.compile("\\[defect]\\s*([^\\[]+)");

    private DefectLabelParser() {
    }

    /**
     * 解析 issues 文本中的 [defect] 标签并计数。
     *
     * @param issues review_record.issues 原文；null/blank 或解析无命中返回空 Map
     * @return 标签名 -> 出现次数（插入序稳定），绝不返回 null
     */
    public static Map<String, Integer> parse(String issues) {
        if (issues == null || issues.isBlank()) {
            return Map.of();
        }
        Map<String, Integer> stats = new LinkedHashMap<>();
        Matcher matcher = DEFECT_PATTERN.matcher(issues);
        while (matcher.find()) {
            String label = normalize(matcher.group(1));
            if (!label.isEmpty()) {
                stats.merge(label, 1, Integer::sum);
            }
        }
        return stats;
    }

    /** 标签归一化：trim + 连续空白折叠为单空格 + 长度截断。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String label = raw.trim().replaceAll("\\s+", " ");
        if (label.length() > LABEL_MAX_LENGTH) {
            label = label.substring(0, LABEL_MAX_LENGTH).trim();
        }
        return label;
    }
}
