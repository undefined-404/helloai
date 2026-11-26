package com.helloai.core.review.support;

import com.helloai.core.review.service.SubTaskReviewService.ReviewVerdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 核验判定静态校验器（`RV-1`，纯函数、零 token、无 Spring 依赖）。
 *
 * <p>对已解析的 {@link ReviewVerdict} 做确定性结构校验，产出分级 {@link Finding}；
 * <b>只产结论不做处置</b>，是否拒收由调用方按档位决定（当前 {@code Warn} 档：只记 timeline，不改判定）。</p>
 *
 * <p>分级：{@link Severity#BLOCK} 仅「逻辑矛盾」——{@code pass=false} 却打满分（{@link #REJECT_WITH_FULL_SCORE}；
 * 反向 {@code pass=true && score<3} 已由 {@code MIN_PASS_SCORE} 拦截）。{@link Severity#WARN}：{@code analysis} 空、
 * {@code acceptanceRef} 非验收原文子串、宽松兜底解析命中、驳回无 {@code [defect]} 条目——只观测。</p>
 *
 * <p>不校验 {@code comment} 质量与 {@code issues} 四元组完整性（提示词风格项，误伤面未知），不清洗判定字段。</p>
 */
public final class ReviewVerdictValidator {

    /** finding 分级：BLOCK 可拒收；WARN 只观测。 */
    public enum Severity {
        BLOCK,
        WARN
    }

    /**
     * 单条校验结论。
     *
     * @param severity 分级
     * @param code     稳定代码（前端 / 统计按此聚合，不改文案）
     * @param detail   人读说明（已截断，可直接进 timeline payload）
     */
    public record Finding(Severity severity, String code, String detail) {
    }

    /** {@code analysis} trim 后为空：核验未逐条对照验收标准。 */
    public static final String ANALYSIS_MISSING = "analysis_missing";

    /** {@code missingEvidence[].acceptanceRef} 不是 {@code acceptance} 原文子串（疑似编造引用）。 */
    public static final String ACCEPTANCE_REF_NOT_GROUNDED = "acceptance_ref_not_grounded";

    /** 判定来自宽松兜底解析（严格 JSON 失败，字段可能不完整）。 */
    public static final String LOOSE_PARSE = "loose_parse";

    /**
     * 引用只在「剥除排版标记 / 折叠空白」后才命中原文（`G-052`）：<b>计入已归属</b>，只作观测。
     *
     * <p>与 {@link #ACCEPTANCE_REF_NOT_GROUNDED} 的区别是「编造」与「抄写格式不同」：
     * 前者是内容对不上，后者内容逐字一致、只是把 `` `code` `` 写成 code、`**粗**` 写成 粗。
     * 若不区分，一次纯格式差异就会经「该条不计覆盖」→ `acceptance_not_covered`(BLOCK)
     * → `INCONCLUSIVE` 把「通过」升级成人工介入（真机实例见 `G-052`）。</p>
     */
    public static final String REF_FORMAT_LOOSE = "grounding_format_loose";

    /** {@code pass=false} 但 {@code issues} 无任一条含 {@code [defect]}（驳回理由未按契约结构化）。 */
    public static final String REJECT_WITHOUT_DEFECT = "reject_without_defect";

    /** {@code pass=false} 却给出满分（逻辑矛盾）。 */
    public static final String REJECT_WITH_FULL_SCORE = "reject_with_full_score";

    /** 驳回与满分互斥的判定门槛（只拦满分：3~4 分驳回可能是合理保守判定）。 */
    private static final int FULL_SCORE = 5;

    /**
     * 允许「归一后才命中」的引用最小长度（字符，`G-052`）。
     *
     * <p>取值理由：中文验收条款的实质片段普遍 ≥8 字，而「① / （a） / 是」这类骨架片段的
     * 归一后长度都 <8 —— 门槛设在 8 之上，让判据只在「抄写格式差异」处松、不在「空壳引用」处松。</p>
     */
    private static final int MIN_LOOSE_REF_CHARS = 8;

    /** 驳回时必须出现在 {@code issues} 里的缺陷标签（提示词契约）。 */
    private static final String DEFECT_TAG = "[defect]";

    /** detail 中最多列出的引用条数（防止 payload 被超量引用撑大）。 */
    private static final int MAX_REFS_IN_DETAIL = 5;

    /** 单条 detail 字符上限。 */
    private static final int MAX_DETAIL_CHARS = 200;

    private ReviewVerdictValidator() {
    }

    /**
     * 对判定做静态校验。
     *
     * <p>顺序固定（BLOCK 在前，WARN 按 {@code analysis → loose_parse → defect → acceptanceRef}），
     * 便于调用方与单测按稳定顺序断言。</p>
     *
     * @param verdict    已解析判定；null ⇒ 空清单（不可解析由调用方另行处置）
     * @param acceptance 子任务验收标准原文（{@code sub_task.acceptance}）；用于引用归属校验
     * @param looseParse 本次判定是否来自宽松兜底解析
     * @return 校验结论（可能为空；不含 null 元素）
     */
    public static List<Finding> validate(ReviewVerdict verdict, String acceptance, boolean looseParse) {
        if (verdict == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        if (Boolean.FALSE.equals(verdict.getPass())
                && verdict.getScore() != null && verdict.getScore() >= FULL_SCORE) {
            findings.add(new Finding(Severity.BLOCK, REJECT_WITH_FULL_SCORE,
                    "驳回却给出满分 score=" + verdict.getScore() + "（pass=false 与 score 互斥）"));
        }
        if (isBlank(verdict.getAnalysis())) {
            findings.add(new Finding(Severity.WARN, ANALYSIS_MISSING,
                    "analysis 为空：核验未逐条对照验收标准"));
        }
        if (looseParse) {
            findings.add(new Finding(Severity.WARN, LOOSE_PARSE,
                    "严格 JSON 解析失败，判定由宽松兜底提取（字段可能不完整）"));
        }
        if (Boolean.FALSE.equals(verdict.getPass()) && !containsDefectTag(verdict.getIssues())) {
            findings.add(new Finding(Severity.WARN, REJECT_WITHOUT_DEFECT,
                    isBlank(verdict.getIssues())
                            ? "驳回但 issues 为空（缺缺陷条目）"
                            : truncate("驳回但 issues 未含 " + DEFECT_TAG + " 条目: "
                                    + verdict.getIssues().trim())));
        }
        findings.addAll(checkAcceptanceRefs(verdict, acceptance));
        return findings;
    }

    /**
     * 引用归属校验：{@code missingEvidence[].acceptanceRef} 必须是 {@code acceptance} 原文子串。
     *
     * <p>验收标准为空时<b>跳过</b>（没有可归属的原文，判「引用不实」会冤枉判定）；
     * 归一化复用 {@link VerdictParser#normalizeMissingEvidence}，与缺失证据清单同一口径。</p>
     *
     * <p>产出两类结论，<b>互斥</b>（`G-052`）：真不归属 → {@link #ACCEPTANCE_REF_NOT_GROUNDED}（WARN，
     * 疑似编造）；仅归一后才归属 → {@link #REF_FORMAT_LOOSE}（WARN，内容对、格式丢）。分开是因为
     * 前者要看，后者只需知道「这份模型有系统性抄写习惯」。</p>
     */
    private static List<Finding> checkAcceptanceRefs(ReviewVerdict verdict, String acceptance) {
        if (isBlank(acceptance)) {
            return List.of();
        }
        List<Map<String, String>> entries = VerdictParser.normalizeMissingEvidence(verdict.getMissingEvidence());
        List<String> ungrounded = new ArrayList<>();
        List<String> looseOnly = new ArrayList<>();
        for (Map<String, String> entry : entries) {
            String ref = entry.get("acceptanceRef");
            if (ref == null) {
                continue;
            }
            if (!isGroundedIn(acceptance, ref)) {
                ungrounded.add(ref);
            } else if (!isGroundedExactly(acceptance, ref)) {
                looseOnly.add(ref);
            }
        }
        List<Finding> found = new ArrayList<>(2);
        if (!ungrounded.isEmpty()) {
            found.add(new Finding(Severity.WARN, ACCEPTANCE_REF_NOT_GROUNDED,
                    summarizeRefs("acceptanceRef 不是验收标准原文子串", ungrounded)));
        }
        if (!looseOnly.isEmpty()) {
            found.add(new Finding(Severity.WARN, REF_FORMAT_LOOSE,
                    summarizeRefs("acceptanceRef 仅在剥除排版标记后才命中原文（已按已归属计入）", looseOnly)));
        }
        return found;
    }

    /** 引用清单的可读摘要（条数 + 前若干条，长文本截断）：两处 WARN 共用一份排版。 */
    private static String summarizeRefs(String label, List<String> refs) {
        StringBuilder detail = new StringBuilder().append(refs.size()).append(" 条 ")
                .append(label).append(": ");
        int limit = Math.min(refs.size(), MAX_REFS_IN_DETAIL);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                detail.append("；");
            }
            detail.append(truncate(refs.get(i)));
        }
        if (refs.size() > MAX_REFS_IN_DETAIL) {
            detail.append("…");
        }
        return detail.toString();
    }

    /**
     * 引用归属判据（`RV-1` / `RV-3` <b>共用同一事实源</b>，防两份判定漂移）。
     *
     * <p>模型被要求引用「验收标准原文子串」，因此合法引用必然满足 {@code 原文.contains(引用)}。
     * 原文为空时一律视为<b>已归属</b>（没有可归属的原文，判「引用不实」会冤枉判定）。</p>
     *
     * <p><b>`G-052` 起两级判据</b>：先字面命中（零容忍路径不变）；字面不中再按
     * {@link #normalizeForGrounding} 双侧归一比一次——只削「行内代码反引号 / 加粗星号 / 连续空白」，
     * <b>不删任何字母、数字、中文与标点</b>，因此「改了内容」的引用归一再比仍不中；且归一命中要求引用
     * 归一后长度 ≥ {@link #MIN_LOOSE_REF_CHARS}，避免「`。` / `①`」这类空壳引用靠归一蒙过。
     * 归一命中不算编造，但由调用方用 {@link #REF_FORMAT_LOOSE} 记一条观测（系统性抄写差异不得静默）。</p>
     *
     * @param source 验收标准原文（整段或单个拆分项）
     * @param ref    模型填写的引用子串
     */
    public static boolean isGroundedIn(String source, String ref) {
        if (isBlank(source) || isBlank(ref)) {
            return true;
        }
        if (source.contains(ref)) {
            return true;
        }
        String normalizedRef = normalizeForGrounding(ref);
        if (normalizedRef.length() < MIN_LOOSE_REF_CHARS) {
            // 短引用只认字面命中：归一后剩两三个字符时几乎能与任何原文撞上，放行等于取消判据
            return false;
        }
        return normalizeForGrounding(source).contains(normalizedRef);
    }

    /**
     * 字面命中判据（`G-052`）：与 {@link #isGroundedIn} 的差别只在「不做归一」。
     *
     * <p>调用方用它区分「逐字引用」与「归一后才成立」——后者要记 {@link #REF_FORMAT_LOOSE}。
     * 两处必须共用 {@link #normalizeForGrounding}，否则「什么算格式差异」会有两个口径。</p>
     */
    public static boolean isGroundedExactly(String source, String ref) {
        if (isBlank(source) || isBlank(ref)) {
            return true;
        }
        return source.contains(ref);
    }

    /**
     * 归属比对前的归一（`G-052`）：削掉<b>排版标记</b>、折叠连续空白。
     *
     * <p>刻意只处理这两类：反引号（`` ` ``）与加粗星号（`*`）在 Markdown 里改变的是呈现而非文字，
     * 模型抄写时最容易丢；空白折叠吸收「换行 / 全角空格 / 多余空格」三种抄写噪声。
     * <b>不</b>处理下划线（`_` 在标识符里有语义）、<b>不</b>做全半角转换、<b>不</b>删标点——
     * 这些一旦归一，「把 A 条款写成 B 条款」也可能撞上，等于用精度换错放。</p>
     */
    static String normalizeForGrounding(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`' || c == '*') {
                continue;
            }
            if (c == '\u00A0' || Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean containsDefectTag(String issues) {
        if (isBlank(issues)) {
            return false;
        }
        return issues.contains(DEFECT_TAG);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "...";
    }
}
