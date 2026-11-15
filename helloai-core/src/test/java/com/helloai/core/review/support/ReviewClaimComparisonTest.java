package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.service.SubTaskReviewService.ReviewVerdict;
import com.helloai.core.review.support.ReviewVerdictValidator.Finding;
import com.helloai.core.review.support.ReviewVerdictValidator.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewClaimComparison}（`RV-11` 声称值 ↔ 实测值）单测。
 *
 * <p>金标准取真机样本：{@code claim=199080} 对 {@code lines=198899} / {@code data=198897}
 * 的双口径差值为 {@code +181} / {@code +183}。</p>
 */
@DisplayName("ReviewClaimComparison（RV-11 声称值比对）")
class ReviewClaimComparisonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MECHANICAL = "[MECHANICAL_CHECK] v1\n"
            + "file id=7 name=detail.csv lines=198899 rows=198900 data=198897 cols=5 "
            + "encoding=utf-8 ragged=0 multiline=0 header=present";

    private static ReviewVerdict verdict(Boolean pass, String coverageJson) throws Exception {
        ReviewVerdict verdict = new ReviewVerdict();
        verdict.setPass(pass);
        verdict.setScore(pass != null && pass ? 5 : 2);
        verdict.setCoverage(MAPPER.readTree(coverageJson));
        return verdict;
    }

    private static String coverage(String criterionRef, String evidenceRef) {
        return "[{\"criterionRef\":\"" + criterionRef + "\",\"status\":\"covered\","
                + "\"method\":\"mechanical\",\"evidenceRef\":\"" + evidenceRef + "\"}]";
    }

    @Test
    @DisplayName("pass=true 且声称值与实测不符 ⇒ BLOCK，双口径差值同时输出（golden +181 / +183）")
    void blocksMismatchOnPass() throws Exception {
        ReviewVerdict verdict = verdict(true,
                coverage("数据行数 199080", "id=7 claim=199080 basis=lines"));

        List<Finding> findings = ReviewClaimComparison.compare(verdict, MECHANICAL);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCK);
        assertThat(findings.get(0).code()).isEqualTo(ReviewClaimComparison.MISMATCH);
        assertThat(findings.get(0).detail())
                .contains("claim=199080")
                .contains("basis=lines")
                .contains("measured=198899")
                .contains("delta_lines=+181")
                .contains("delta_data=+183");
    }

    @Test
    @DisplayName("pass=true 且声称值与指定口径一致 ⇒ 无 finding")
    void noFindingWhenClaimMatches() throws Exception {
        ReviewVerdict verdict = verdict(true,
                coverage("数据行数 198897", "id=7 claim=198897 basis=data"));

        assertThat(ReviewClaimComparison.compare(verdict, MECHANICAL)).isEmpty();
    }

    @Test
    @DisplayName("claim 带千分位逗号 ⇒ 去逗号后按等价数值比对（不误报 BLOCK）")
    void thousandSeparatedClaimParses() throws Exception {
        ReviewVerdict verdict = verdict(true,
                coverage("数据行数 198897", "id=7 claim=198,897 basis=data"));

        assertThat(ReviewClaimComparison.compare(verdict, MECHANICAL)).isEmpty();

        ReviewVerdict mismatch = verdict(true,
                coverage("数据行数 198897", "id=7 claim=198,899 basis=data"));
        assertThat(ReviewClaimComparison.compare(mismatch, MECHANICAL))
                .extracting(Finding::severity)
                .containsExactly(Severity.BLOCK);
    }

    @Test
    @DisplayName("驳回侧（pass=false）声称值不符 ⇒ 只 WARN，不阻断驳回结论")
    void mismatchOnRejectIsWarnOnly() throws Exception {
        ReviewVerdict verdict = verdict(false,
                coverage("数据行数 199080", "id=7 claim=199080 basis=lines"));

        List<Finding> findings = ReviewClaimComparison.compare(verdict, MECHANICAL);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.WARN);
        assertThat(findings.get(0).code()).isEqualTo(ReviewClaimComparison.MISMATCH_ON_REJECT);
    }

    @Test
    @DisplayName("pass 缺失（宽松解析）按驳回侧处理")
    void nullPassTreatedAsReject() throws Exception {
        ReviewVerdict verdict = verdict(null,
                coverage("数据行数 199080", "id=7 claim=199080 basis=lines"));

        assertThat(ReviewClaimComparison.compare(verdict, MECHANICAL))
                .extracting(Finding::severity)
                .containsExactly(Severity.WARN);
    }

    @Test
    @DisplayName("声称值无法定位机械实测值 ⇒ WARN claim_without_measured（不猜）")
    void warnsWhenMeasuredUnavailable() throws Exception {
        ReviewVerdict verdict = verdict(true,
                coverage("数据行数 199080", "id=8 claim=199080 basis=lines"));

        List<Finding> findings = ReviewClaimComparison.compare(verdict, MECHANICAL);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.WARN);
        assertThat(findings.get(0).code()).isEqualTo(ReviewClaimComparison.WITHOUT_MEASURED);
    }

    @Test
    @DisplayName("声称值未标 basis ⇒ WARN claim_basis_missing（口径未知不做比对）")
    void warnsWhenBasisMissing() throws Exception {
        ReviewVerdict verdict = verdict(true,
                coverage("数据行数 199080", "id=7 claim=199080"));

        List<Finding> findings = ReviewClaimComparison.compare(verdict, MECHANICAL);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.WARN);
        assertThat(findings.get(0).code()).isEqualTo(ReviewClaimComparison.BASIS_MISSING);
    }

    @Test
    @DisplayName("basis 大小写不敏感：basis=LINES 按 lines 口径比对，不落 basis_missing")
    void basisIsCaseInsensitive() throws Exception {
        ReviewVerdict verdict = verdict(true,
                coverage("数据行数 198899", "id=7 claim=198899 basis=LINES"));

        assertThat(ReviewClaimComparison.compare(verdict, MECHANICAL)).isEmpty();
    }

    @Test
    @DisplayName("coverage 未写 claim ⇒ 不比对（避免从报告正文抽数字）")
    void ignoresCoverageWithoutClaim() throws Exception {
        ReviewVerdict verdict = verdict(true,
                coverage("数据行数", "id=7 name=detail.csv"));

        assertThat(ReviewClaimComparison.compare(verdict, MECHANICAL)).isEmpty();
    }

    @Test
    @DisplayName("机械段为空 / verdict 为 null ⇒ 空清单")
    void toleratesMissingInputs() throws Exception {
        assertThat(ReviewClaimComparison.compare(null, MECHANICAL)).isEmpty();
        assertThat(ReviewClaimComparison.compare(verdict(true,
                coverage("数据行数 199080", "id=7 claim=199080 basis=lines")), null)).hasSize(1);
    }
}
