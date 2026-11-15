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
 * 声称值 ↔ 实测值比对（`RV-11` 承接 `RV-10` M2，纯函数、零 token）。
 *
 * <p>解析 verdict 后读 {@code coverage[].evidenceRef} 的 {@code claim=}，不从报告正文抽数字
 * （机械段在 LLM 调用前装配，拿不到声称值，故分离）；{@code delta_lines} 与 {@code delta_data}
 * 双口径恒并列，模型用 {@code basis=} 指明口径。</p>
 */
public final class ReviewClaimComparison {

    /** 通过判定但声称值与实测值不符（硬缺陷：数字对不上却判通过）。 */
    public static final String MISMATCH = "claim_measured_mismatch";

    /** 驳回判定但声称值与实测值不符（只告警：不通过结论本身不受影响）。 */
    public static final String MISMATCH_ON_REJECT = "claim_measured_mismatch_on_reject";

    /** 有声称值但机械段无法定位唯一文件（不猜、不阻断）。 */
    public static final String WITHOUT_MEASURED = "claim_without_measured";

    /** 声称值未带口径（无法做确定性比对，提示补 `basis=`）。 */
    public static final String BASIS_MISSING = "claim_basis_missing";

    /** 数字可带千分位逗号（reviewer 常写 {@code claim=199,080}），捕获组仍输出纯数字供 parse。 */
    private static final Pattern CLAIM = Pattern.compile("claim\\s*=\\s*([,0-9]+)");
    /** 口径大小写不敏感（模型偶写 `basis=LINES`）；大小写不匹配会误落 `basis_missing`。 */
    private static final Pattern BASIS =
            Pattern.compile("basis\\s*=\\s*(lines|rows|data)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID = Pattern.compile("id\\s*=\\s*([,0-9]+)");

    private static final int MAX_DETAIL_CHARS = 200;

    private ReviewClaimComparison() {
    }

    /**
     * 对判定里的声称值做确定性比对。
     *
     * @param verdict        已解析判定；null 返回空清单
     * @param mechanicalText {@code [MECHANICAL_CHECK]} 段（可空）
     * @return findings（`pass=true` 的不符为 BLOCK，驳回侧为 WARN）
     */
    public static List<Finding> compare(ReviewVerdict verdict, String mechanicalText) {
        if (verdict == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        List<String> withoutMeasured = new ArrayList<>();
        List<String> basisMissing = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        List<String> rejectMismatches = new ArrayList<>();
        boolean pass = Boolean.TRUE.equals(verdict.getPass());
        for (CoverageItem item : ReviewCoverageParser.parse(verdict.getCoverage())) {
            Matcher claimMatcher = CLAIM.matcher(item.evidenceRef());
            if (!claimMatcher.find()) {
                continue;
            }
            long claim = parseLong(claimMatcher.group(1));
            Matcher idMatcher = ID.matcher(item.evidenceRef());
            Long attachmentId = null;
            if (idMatcher.find()) {
                attachmentId = parseLong(idMatcher.group(1));
            }
            ReviewMechanicalCheck.Measure measured =
                    ReviewMechanicalCheck.measureOf(mechanicalText, attachmentId);
            if (measured == null) {
                withoutMeasured.add(label(item));
                continue;
            }
            Matcher basisMatcher = BASIS.matcher(item.evidenceRef());
            if (!basisMatcher.find()) {
                basisMissing.add(label(item) + " claim=" + claim);
                continue;
            }
            long delta = claim - valueOf(measured, basisMatcher.group(1));
            if (delta == 0) {
                continue;
            }
            String detail = label(item) + " claim=" + claim + " basis=" + basisMatcher.group(1)
                    + " measured=" + valueOf(measured, basisMatcher.group(1))
                    + " delta_lines=" + signed(claim - measured.lines())
                    + " delta_data=" + signed(claim - measured.data());
            if (pass) {
                mismatches.add(detail);
            } else {
                rejectMismatches.add(detail);
            }
        }
        if (!withoutMeasured.isEmpty()) {
            findings.add(new Finding(Severity.WARN, WITHOUT_MEASURED,
                    listDetail(withoutMeasured, " 条声称值无法定位机械实测值（未做比对）: ")));
        }
        if (!basisMissing.isEmpty()) {
            findings.add(new Finding(Severity.WARN, BASIS_MISSING,
                    listDetail(basisMissing, " 条声称值未标 basis=lines|rows|data（口径未知，未比对）: ")));
        }
        if (!mismatches.isEmpty()) {
            findings.add(new Finding(Severity.BLOCK, MISMATCH,
                    listDetail(mismatches, " 条声称值与机械实测值不符（pass=true）: ")));
        }
        if (!rejectMismatches.isEmpty()) {
            findings.add(new Finding(Severity.WARN, MISMATCH_ON_REJECT,
                    listDetail(rejectMismatches, " 条声称值与机械实测值不符（驳回侧仅告警）: ")));
        }
        return findings;
    }

    private static long valueOf(ReviewMechanicalCheck.Measure measured, String basis) {
        return switch (basis) {
            case "rows" -> measured.rows();
            case "data" -> measured.data();
            default -> measured.lines();
        };
    }

    private static String label(CoverageItem item) {
        if (!item.criterionRef().isEmpty()) {
            return item.criterionRef();
        }
        return item.evidenceRef().isEmpty() ? "(无引用)" : item.evidenceRef();
    }

    private static String signed(long value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static String listDetail(List<String> values, String label) {
        StringBuilder detail = new StringBuilder().append(values.size()).append(label);
        for (int i = 0; i < values.size() && i < 5; i++) {
            if (i > 0) {
                detail.append("；");
            }
            detail.append(values.get(i));
        }
        if (values.size() > 5) {
            detail.append("…");
        }
        String text = detail.toString();
        return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "...";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
