package com.helloai.core.review.support;

import com.helloai.core.review.service.SubTaskReviewService.ReviewVerdict;
import com.helloai.core.review.support.ReviewCoverageParser.CoverageItem;
import com.helloai.core.review.support.ReviewVerdictValidator.Finding;
import com.helloai.core.review.support.ReviewVerdictValidator.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 驳回侧「证据不可得」出口（`RV-11`，纯函数、零 token）。
 *
 * <p>把「平台没给 reviewer 看全」导致的驳回降级为存疑，避免消耗返工预算去补「平台注入不足」。
 * 四条同时成立才降级：① {@code pass=false}；② 无 {@code failed} 条目；③ 至少一条
 * {@code not_verifiable}；④ 平台侧有可指认缺口（引用 id 在台账不可读/被截断，或未引用 id 但注入正文有截断）。
 * 降级产出 {@code BLOCK} → 引擎 {@code inconclusive()} → 服务侧 {@code INCONCLUSIVE} 且不返工。</p>
 */
public final class ReviewEvidenceUnavailability {

    /** 驳回侧证据不可得（BLOCK：不得按驳回落地、不得消耗返工预算）。 */
    public static final String CODE = "evidence_unavailable_rejection";

    /** 驳回侧引用了台账中不存在的附件 id（WARN：只留痕，不构成缺口）。 */
    public static final String UNKNOWN_ID = "evidence_ref_unknown_id";

    /** `id=` 容忍千分位逗号（与 {@code ReviewCoverageCritic} / {@code ReviewClaimComparison} 同一解析口径）。 */
    private static final Pattern ID = Pattern.compile("id\\s*=\\s*([,0-9]+)");

    private static final int MAX_ITEMS_IN_DETAIL = 5;
    private static final int MAX_DETAIL_CHARS = 200;

    private ReviewEvidenceUnavailability() {
    }

    /**
     * 判定是否构成「证据不可得型驳回」。
     *
     * @param verdict 已解析判定；null / 通过返回 null
     * @param ledger  {@link ReviewEvidenceLedger} 渲染文本（可空）
     * @return BLOCK finding；不满足降级条件返回 null
     */
    public static Finding detect(ReviewVerdict verdict, String ledger) {
        if (verdict == null || Boolean.TRUE.equals(verdict.getPass())) {
            return null;
        }
        List<CoverageItem> coverage = ReviewCoverageParser.parse(verdict.getCoverage());
        if (coverage.isEmpty()) {
            return null;
        }
        boolean anyFailed = false;
        List<CoverageItem> notVerifiable = new ArrayList<>();
        for (CoverageItem item : coverage) {
            if (ReviewCoverageParser.STATUS_FAILED.equals(item.status())) {
                anyFailed = true;
            }
            if (ReviewCoverageParser.STATUS_NOT_VERIFIABLE.equals(item.status())) {
                notVerifiable.add(item);
            }
        }
        if (anyFailed || notVerifiable.isEmpty()) {
            return null;
        }
        boolean gap = false;
        for (CoverageItem item : notVerifiable) {
            Matcher matcher = ID.matcher(item.evidenceRef());
            if (matcher.find()) {
                gap = ReviewEvidenceLedger.gap(ledger, parseLong(matcher.group(1)));
            } else {
                gap = ReviewEvidenceLedger.gap(ledger, null);
            }
            if (gap) {
                break;
            }
        }
        if (!gap) {
            return null;
        }
        return new Finding(Severity.BLOCK, CODE, detail(notVerifiable));
    }

    /**
     * 驳回侧引用了台账中不存在的附件 id（`RV-11` 口径 4）：只出 {@code WARN}，不构成降级依据。
     *
     * <p>为什么不参与降级：{@link ReviewEvidenceLedger#gap} 只在「台账整体为空」或「条目溢出」时
     * 把未知 id 算作缺口；台账非空且无溢出却查不到，只能说明 id 是编的——编造不该换来「转人工」
     * 这个奖励。但「模型写了个不存在的附件 id」本身必须可观测，所以这里留一条 {@code WARN}。</p>
     *
     * @param verdict 已解析判定；null / 通过 / 无 coverage 返回 null
     * @param ledger  {@link ReviewEvidenceLedger} 渲染文本（可空）
     * @return {@code WARN} finding；无未知 id 或无法判定时返回 null
     */
    public static Finding unknownIds(ReviewVerdict verdict, String ledger) {
        if (verdict == null || Boolean.TRUE.equals(verdict.getPass())) {
            return null;
        }
        List<CoverageItem> coverage = ReviewCoverageParser.parse(verdict.getCoverage());
        if (coverage.isEmpty() || ReviewEvidenceLedger.parse(ledger).isEmpty()
                || ReviewEvidenceLedger.omittedCount(ledger) > 0) {
            return null;
        }
        List<String> unknown = new ArrayList<>();
        for (CoverageItem item : coverage) {
            Matcher matcher = ID.matcher(item.evidenceRef());
            if (!matcher.find()) {
                continue;
            }
            long id = parseLong(matcher.group(1));
            if (id > 0 && ReviewEvidenceLedger.find(ledger, id) == null) {
                unknown.add(String.valueOf(id));
            }
        }
        if (unknown.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder()
                .append(unknown.size())
                .append(" 条 coverage 引用了台账中不存在的附件 id（不计入证据缺口）: ");
        for (int i = 0; i < unknown.size() && i < MAX_ITEMS_IN_DETAIL; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append("id=").append(unknown.get(i));
        }
        if (unknown.size() > MAX_ITEMS_IN_DETAIL) {
            sb.append("…");
        }
        String text = sb.toString();
        return new Finding(Severity.WARN, UNKNOWN_ID,
                text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "...");
    }

    private static String detail(List<CoverageItem> items) {
        StringBuilder sb = new StringBuilder()
                .append(items.size())
                .append(" 条验收点因平台证据不可得判 not_verifiable，且无任何 failed 条目；")
                .append("不得按驳回落地，转人工补证据: ");
        for (int i = 0; i < items.size() && i < MAX_ITEMS_IN_DETAIL; i++) {
            if (i > 0) {
                sb.append("；");
            }
            CoverageItem item = items.get(i);
            sb.append(item.criterionRef().isEmpty() ? item.evidenceRef() : item.criterionRef());
        }
        if (items.size() > MAX_ITEMS_IN_DETAIL) {
            sb.append("…");
        }
        return sb.length() <= MAX_DETAIL_CHARS ? sb.toString() : sb.substring(0, MAX_DETAIL_CHARS) + "...";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
