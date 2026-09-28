package com.helloai.core.review.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最终报告交付保真**确定性**校验器（3A 机械前置门）。
 *
 * <p><b>动机</b>：报告链的核验闭环（{@code FinalReportReviewListener}）此前只有 LLM 单轨判定，
 * 实测出现「报告正文写『完整矩阵见分册第4章』、违反模板《绝对禁止清单》却仍被判 {@code pass}」的漏检。
 * 本类把**可机械判定**的违规项从 LLM 判定中剥离出来，在调用审查 LLM 之前先跑一遍，
 * 命中硬违规即机械驳回——不给 LLM 放过的机会，且零 token 成本、结果可复现。</p>
 *
 * <p><b>违规分两级</b>：</p>
 * <ul>
 *   <li><b>HARD（硬违规，直接驳回）</b>：空壳引用（把契约性事实外置为「见分册 / 详见子任务X / 详见前文」）、
 *       代码围栏失衡（Markdown 结构损坏）。</li>
 *   <li><b>SOFT（软违规，落告警事件交 LLM 复核）</b>：跨章逐字重复（同一 ≥4 行内容块出现在 ≥2 章）、
 *       覆盖追溯表缺失。</li>
 * </ul>
 *
 * <p><b>刻意不判</b>：正文出现「附件」字样（二进制/大文件交付物引用属合法，
 * 仅表格/清单外置到附件才算空壳引用——后者由软违规「覆盖追溯表缺失 + LLM 复核」共同兜住）。</p>
 *
 * <p>纯静态、无 Spring 依赖、无副作用；内部任何异常一律吞掉返回空结果——
 * 校验器自身故障绝不阻断审查主链（与报告生成「只增不减」的容错哲学一致）。</p>
 */
public final class FinalReportFidelityChecker {

    /**
     * 空壳引用禁用词：命中即视为契约性事实被外置
     * （对应模板《绝对禁止清单》的「回查推诿」「压缩表格」两行）。
     */
    private static final List<String> SHELL_REFERENCE_PHRASES = List.of(
            "详见子任务", "见子任务", "参见子任务", "子任务产出见",
            "详见分册", "见分册", "分册第",
            "详见前文", "详见上文", "详见原始", "回查子任务", "不再赘述");

    /** 章节标题（二级标题）：跨章重复检测的分章依据。 */
    private static final Pattern CHAPTER_HEADING = Pattern.compile("(?m)^##\\s+.*$");
    /** 软违规：同一「≥N 行连续内容块」在 ≥2 章逐字重复的最小行数阈值。 */
    private static final int DUPLICATE_BLOCK_MIN_LINES = 4;
    /** 单类违规最多上报条数（防 timeline payload 膨胀）。 */
    private static final int MAX_REPORTED = 5;
    /** 违规摘要片段长度（单行）。 */
    private static final int SNIPPET_LENGTH = 60;

    private FinalReportFidelityChecker() {
    }

    /** 违规级别：HARD 机械驳回 / SOFT 告警交 LLM 复核。 */
    public enum Severity {HARD, SOFT}

    /** 单条违规：级别 + 规则名 + 可直接指导返工的描述。 */
    public record Violation(Severity severity, String rule, String detail) {
    }

    /** 校验结果：违规清单（可能为空）。 */
    public record Result(List<Violation> violations) {

        public boolean hasHard() {
            return violations.stream().anyMatch(v -> v.severity() == Severity.HARD);
        }

        public boolean hasSoft() {
            return violations.stream().anyMatch(v -> v.severity() == Severity.SOFT);
        }

        /** 硬违规汇总为可注入返工 Prompt 的 issue 文本。 */
        public String hardIssueList() {
            return issueList(Severity.HARD);
        }

        /** 软违规汇总（供告警事件 payload）。 */
        public String softIssueList() {
            return issueList(Severity.SOFT);
        }

        private String issueList(Severity severity) {
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (Violation v : violations) {
                if (v.severity() != severity) {
                    continue;
                }
                sb.append(idx++).append(". [").append(v.rule()).append("] ").append(v.detail()).append('\n');
            }
            return sb.toString().trim();
        }
    }

    /** 执行全部确定性校验；{@code report} 为 null/空返回硬违规（空报告不合法）。 */
    public static Result check(String report) {
        List<Violation> violations = new ArrayList<>();
        try {
            if (report == null || report.isBlank()) {
                violations.add(new Violation(Severity.HARD, "empty_report", "报告正文为空"));
                return new Result(List.copyOf(violations));
            }
            detectShellReferences(report, violations);
            detectUnbalancedFence(report, violations);
            detectMissingCoverageTable(report, violations);
            detectCrossChapterDuplicates(report, violations);
        } catch (Exception e) {
            // 校验器自身异常不阻断审查（回退 LLM 单轨）
            return new Result(List.of());
        }
        return new Result(List.copyOf(violations));
    }

    /** 硬违规①：契约性事实外置表述（「见分册」「详见子任务X」「详见前文」等）。 */
    private static void detectShellReferences(String report, List<Violation> out) {
        for (String phrase : SHELL_REFERENCE_PHRASES) {
            int from = 0;
            while (countByRule(out, "shell_reference") < MAX_REPORTED) {
                int idx = report.indexOf(phrase, from);
                if (idx < 0) {
                    break;
                }
                out.add(new Violation(Severity.HARD, "shell_reference",
                        "正文出现契约性事实外置表述「" + phrase + "」：" + snippet(report, idx)));
                from = idx + phrase.length();
            }
        }
    }

    /** 硬违规②：代码围栏 ``` 数量为奇数 → Markdown 结构损坏（块未闭合）。 */
    private static void detectUnbalancedFence(String report, List<Violation> out) {
        int fences = 0;
        int idx = report.indexOf("```");
        while (idx >= 0) {
            fences++;
            idx = report.indexOf("```", idx + 3);
        }
        if (fences % 2 != 0) {
            out.add(new Violation(Severity.HARD, "code_fence_unbalanced",
                    "代码围栏 ``` 数量为奇数（" + fences + "），存在未闭合代码块"));
        }
    }

    /** 软违规①：未检出覆盖追溯表（模板强制结构第2步，章节归属唯一权威）。 */
    private static void detectMissingCoverageTable(String report, List<Violation> out) {
        if (report.contains("覆盖追溯表") || report.contains("覆盖子任务")) {
            return;
        }
        out.add(new Violation(Severity.SOFT, "coverage_table_missing",
                "未检出「覆盖追溯表」（模板强制结构第2步），章节↔子任务归属不可核"));
    }

    /** 软违规②：同一 ≥N 行内容块在 ≥2 章逐字重复（模板目标1「同一结论不重复出现」）。 */
    private static void detectCrossChapterDuplicates(String report, List<Violation> out) {
        List<String> chapters = splitChapters(report);
        if (chapters.size() < 2) {
            return;
        }
        Map<String, Set<Integer>> blockToChapters = new LinkedHashMap<>();
        for (int chapterIndex = 0; chapterIndex < chapters.size(); chapterIndex++) {
            for (String block : extractBlocks(chapters.get(chapterIndex))) {
                blockToChapters.computeIfAbsent(block, k -> new LinkedHashSet<>()).add(chapterIndex);
            }
        }
        int reported = 0;
        for (Map.Entry<String, Set<Integer>> entry : blockToChapters.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            out.add(new Violation(Severity.SOFT, "cross_chapter_duplicate",
                    "同一 " + lineCount(entry.getKey()) + " 行内容块在 "
                            + entry.getValue().size() + " 个章节逐字重复：" + snippet(entry.getKey(), 0)));
            if (++reported >= MAX_REPORTED) {
                return;
            }
        }
    }

    /** 按二级标题切分章节正文（标题行归入下一章起点的前一段）。 */
    private static List<String> splitChapters(String report) {
        List<String> chapters = new ArrayList<>();
        Matcher matcher = CHAPTER_HEADING.matcher(report);
        List<int[]> spans = new ArrayList<>();
        while (matcher.find()) {
            spans.add(new int[]{matcher.start(), matcher.end()});
        }
        for (int i = 0; i < spans.size(); i++) {
            int contentStart = spans.get(i)[1];
            int contentEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : report.length();
            chapters.add(report.substring(contentStart, Math.max(contentStart, contentEnd)));
        }
        return chapters;
    }

    /** 抽取长度 ≥ {@link #DUPLICATE_BLOCK_MIN_LINES} 行的连续非空行块（逐行 trim 归一化）。 */
    private static List<String> extractBlocks(String chapterText) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentLines = 0;
        for (String rawLine : chapterText.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                flushBlock(blocks, current, currentLines);
                current.setLength(0);
                currentLines = 0;
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            currentLines++;
        }
        flushBlock(blocks, current, currentLines);
        return blocks;
    }

    private static void flushBlock(List<String> blocks, StringBuilder current, int lines) {
        if (lines >= DUPLICATE_BLOCK_MIN_LINES) {
            blocks.add(current.toString());
        }
    }

    private static int countByRule(List<Violation> violations, String rule) {
        int count = 0;
        for (Violation v : violations) {
            if (rule.equals(v.rule())) {
                count++;
            }
        }
        return count;
    }

    private static int lineCount(String block) {
        int count = 1;
        for (int i = 0; i < block.length(); i++) {
            if (block.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** 截取 index 所在行的前 {@link #SNIPPET_LENGTH} 字符（单行，便于事件展示）。 */
    private static String snippet(String text, int index) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int safeIndex = Math.max(0, Math.min(index, text.length() - 1));
        int lineStart = text.lastIndexOf('\n', safeIndex) + 1;
        int lineEnd = text.indexOf('\n', safeIndex);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        String line = text.substring(lineStart, lineEnd).trim();
        return line.length() <= SNIPPET_LENGTH ? line : line.substring(0, SNIPPET_LENGTH) + "…";
    }
}
