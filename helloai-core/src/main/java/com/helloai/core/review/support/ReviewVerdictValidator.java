package com.helloai.core.review.support;

import com.helloai.core.review.service.SubTaskReviewService.ReviewVerdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 核验判定静态校验器（`RV-1`，纯函数、零 token、无 Spring 依赖）。
 *
 * <p><b>职责</b>：对已解析的 {@link ReviewVerdict} 做确定性结构校验，产出分级
 * {@link Finding}。<b>只产结论，不做处置</b>——是否拒收由调用方按档位决定
 * （本批次为 `Warn` 档：只记 timeline 事件，不改判定）。</p>
 *
 * <p><b>分级口径（按真机数据定，见实施计划 §4 D1）</b>：</p>
 * <ul>
 *     <li>{@link Severity#BLOCK}：仅「逻辑矛盾」一条——{@code pass=false} 却打满分
 *         （{@link #REJECT_WITH_FULL_SCORE}）。反向矛盾 {@code pass=true && score < 3}
 *         已由 {@code SubTaskReviewServiceImpl.MIN_PASS_SCORE} 拦截，此处不重复。</li>
 *     <li>{@link Severity#WARN}：{@code analysis} 为空、{@code missingEvidence} 的
 *         {@code acceptanceRef} 不是验收标准原文子串、宽松兜底解析命中、驳回但无
 *         {@code [defect]} 条目。真机语料（n=12）上四项零命中，不足以证明零误伤，
 *         故先只观测。</li>
 * </ul>
 *
 * <p><b>不做</b>：不校验 {@code comment} 质量，不校验 {@code issues} 四元组完整性
 * （属提示词风格项，误伤面未知），不清洗判定字段。</p>
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

    /** {@code pass=false} 但 {@code issues} 无任一条含 {@code [defect]}（驳回理由未按契约结构化）。 */
    public static final String REJECT_WITHOUT_DEFECT = "reject_without_defect";

    /** {@code pass=false} 却给出满分（逻辑矛盾）。 */
    public static final String REJECT_WITH_FULL_SCORE = "reject_with_full_score";

    /** 驳回与满分互斥的判定门槛（只拦满分：3~4 分驳回可能是合理保守判定）。 */
    private static final int FULL_SCORE = 5;

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
        Finding grounding = checkAcceptanceRefs(verdict, acceptance);
        if (grounding != null) {
            findings.add(grounding);
        }
        return findings;
    }

    /**
     * 引用归属校验：{@code missingEvidence[].acceptanceRef} 必须是 {@code acceptance} 原文子串。
     *
     * <p>验收标准为空时<b>跳过</b>（没有可归属的原文，判「引用不实」会冤枉判定）；
     * 归一化复用 {@link VerdictParser#normalizeMissingEvidence}，与缺失证据清单同一口径。</p>
     */
    private static Finding checkAcceptanceRefs(ReviewVerdict verdict, String acceptance) {
        if (isBlank(acceptance)) {
            return null;
        }
        List<Map<String, String>> entries = VerdictParser.normalizeMissingEvidence(verdict.getMissingEvidence());
        List<String> ungrounded = new ArrayList<>();
        for (Map<String, String> entry : entries) {
            String ref = entry.get("acceptanceRef");
            if (ref == null || isGroundedIn(acceptance, ref)) {
                continue;
            }
            ungrounded.add(ref);
        }
        if (ungrounded.isEmpty()) {
            return null;
        }
        StringBuilder detail = new StringBuilder()
                .append(ungrounded.size()).append(" 条 acceptanceRef 不是验收标准原文子串: ");
        for (int i = 0; i < ungrounded.size() && i < MAX_REFS_IN_DETAIL; i++) {
            if (i > 0) {
                detail.append("；");
            }
            detail.append(truncate(ungrounded.get(i)));
        }
        if (ungrounded.size() > MAX_REFS_IN_DETAIL) {
            detail.append("…");
        }
        return new Finding(Severity.WARN, ACCEPTANCE_REF_NOT_GROUNDED, truncate(detail.toString()));
    }

    /**
     * 引用归属判据（`RV-1` / `RV-3` <b>共用同一事实源</b>，防两份判定漂移）。
     *
     * <p>模型被要求引用「验收标准原文子串」，因此合法引用必然满足 {@code 原文.contains(引用)}。
     * 原文为空时一律视为<b>已归属</b>（没有可归属的原文，判「引用不实」会冤枉判定）。</p>
     *
     * @param source 验收标准原文（整段或单个拆分项）
     * @param ref    模型填写的引用子串
     */
    public static boolean isGroundedIn(String source, String ref) {
        if (isBlank(source) || isBlank(ref)) {
            return true;
        }
        return source.contains(ref);
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
