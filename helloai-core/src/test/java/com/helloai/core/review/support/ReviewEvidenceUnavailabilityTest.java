package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.service.SubTaskReviewService.ReviewVerdict;
import com.helloai.core.review.support.ReviewVerdictValidator.Finding;
import com.helloai.core.review.support.ReviewVerdictValidator.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewEvidenceUnavailability}（`RV-11.2` 证据不可得出口）单测。
 *
 * <p>四条件同时成立才降级：pass=false + 无 failed + 有 not_verifiable + 台账确有缺口。</p>
 */
@DisplayName("ReviewEvidenceUnavailability（RV-11.2 证据不可得出口）")
class ReviewEvidenceUnavailabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TRUNCATED_LEDGER = "[EVIDENCE_LEDGER] v1 totalChars=5000 entries=1 omitted=0\n"
            + "entry id=7 name=data.csv chars=155000 status=truncated range=head+tail shown=5000";

    private static final String CLEAN_LEDGER = "[EVIDENCE_LEDGER] v1 totalChars=9000 entries=1 omitted=0\n"
            + "entry id=7 name=data.csv chars=9000 status=injected range=full shown=9000";

    private static ReviewVerdict verdict(Boolean pass, String coverageJson) throws Exception {
        ReviewVerdict verdict = new ReviewVerdict();
        verdict.setPass(pass);
        verdict.setCoverage(MAPPER.readTree(coverageJson));
        return verdict;
    }

    private static String coverage(String status, String evidenceRef) {
        return "[{\"criterionRef\":\"验收点 A\",\"status\":\"" + status + "\","
                + "\"method\":\"attachment\",\"evidenceRef\":\"" + evidenceRef + "\"}]";
    }

    @Test
    @DisplayName("四条件成立（pass=false + 无 failed + not_verifiable + 台账截断）⇒ BLOCK")
    void detectsEvidenceUnavailableRejection() throws Exception {
        ReviewVerdict verdict = verdict(false,
                coverage("not_verifiable", "id=7 name=data.csv"));

        Finding finding = ReviewEvidenceUnavailability.detect(verdict, TRUNCATED_LEDGER);

        assertThat(finding).isNotNull();
        assertThat(finding.severity()).isEqualTo(Severity.BLOCK);
        assertThat(finding.code()).isEqualTo(ReviewEvidenceUnavailability.CODE);
        assertThat(finding.detail()).contains("验收点 A").contains("转人工补证据");
    }

    @Test
    @DisplayName("台账无缺口（完整注入）⇒ 不降级，按普通驳回处理")
    void noGapMeansNormalRejection() throws Exception {
        ReviewVerdict verdict = verdict(false,
                coverage("not_verifiable", "id=7 name=data.csv"));

        assertThat(ReviewEvidenceUnavailability.detect(verdict, CLEAN_LEDGER)).isNull();
    }

    @Test
    @DisplayName("存在 failed 条目 ⇒ 不是纯证据缺口，不降级")
    void failedItemBlocksDowngrade() throws Exception {
        String coverageJson = "["
                + "{\"criterionRef\":\"验收点 A\",\"status\":\"not_verifiable\",\"method\":\"attachment\",\"evidenceRef\":\"id=7\"},"
                + "{\"criterionRef\":\"验收点 B\",\"status\":\"failed\",\"method\":\"attachment\",\"evidenceRef\":\"x\"}]";
        ReviewVerdict verdict = verdict(false, coverageJson);

        assertThat(ReviewEvidenceUnavailability.detect(verdict, TRUNCATED_LEDGER)).isNull();
    }

    @Test
    @DisplayName("pass=true / 无 coverage ⇒ 不降级")
    void ignoresPassAndEmptyCoverage() throws Exception {
        assertThat(ReviewEvidenceUnavailability.detect(verdict(true,
                coverage("not_verifiable", "id=7")), TRUNCATED_LEDGER)).isNull();
        assertThat(ReviewEvidenceUnavailability.detect(verdict(false, "[]"), TRUNCATED_LEDGER)).isNull();
        assertThat(ReviewEvidenceUnavailability.detect(null, TRUNCATED_LEDGER)).isNull();
    }

    @Test
    @DisplayName("未写 id 的 not_verifiable ⇒ 只认「注入正文存在截断」，不因 absent 单独成立")
    void missingIdFallsBackToAnyGap() throws Exception {
        ReviewVerdict verdict = verdict(false,
                coverage("not_verifiable", "data.csv 正文被截断"));

        assertThat(ReviewEvidenceUnavailability.detect(verdict, TRUNCATED_LEDGER)).isNotNull();
        assertThat(ReviewEvidenceUnavailability.detect(verdict, CLEAN_LEDGER)).isNull();
    }

    @Test
    @DisplayName("台账 absent（附件不可读）同样构成缺口")
    void absentEntryCountsAsGap() throws Exception {
        String absentLedger = "[EVIDENCE_LEDGER] v1 totalChars=0 entries=1 omitted=0\n"
                + "entry id=9 name=broken.csv chars=0 status=absent reason=unreadable";
        ReviewVerdict verdict = verdict(false,
                coverage("not_verifiable", "id=9 name=broken.csv"));

        assertThat(ReviewEvidenceUnavailability.detect(verdict, absentLedger)).isNotNull();
    }

    @Test
    @DisplayName("台账为空 / null ⇒ 指定 id 视为缺口（平台未提供可核证据）")
    void emptyLedgerIsGap() throws Exception {
        ReviewVerdict verdict = verdict(false,
                coverage("not_verifiable", "id=7 name=data.csv"));

        assertThat(ReviewEvidenceUnavailability.detect(verdict, null)).isNotNull();
    }

    @Test
    @DisplayName("编造附件 id：不降级（不算缺口）+ 出 WARN 留痕")
    void fabricatedIdWarnsWithoutDowngrade() throws Exception {
        ReviewVerdict verdict = verdict(false,
                coverage("not_verifiable", "id=99 name=ghost.csv"));

        assertThat(ReviewEvidenceUnavailability.detect(verdict, CLEAN_LEDGER)).isNull();

        Finding finding = ReviewEvidenceUnavailability.unknownIds(verdict, CLEAN_LEDGER);
        assertThat(finding).isNotNull();
        assertThat(finding.severity()).isEqualTo(Severity.WARN);
        assertThat(finding.code()).isEqualTo(ReviewEvidenceUnavailability.UNKNOWN_ID);
        assertThat(finding.detail()).contains("id=99");
    }

    @Test
    @DisplayName("unknownIds 只在台账完整时告警：id 命中 / 台账溢出 / 通过判定都不告警")
    void unknownIdsOnlyForCompleteLedger() throws Exception {
        ReviewVerdict known = verdict(false, coverage("not_verifiable", "id=7 name=data.csv"));
        assertThat(ReviewEvidenceUnavailability.unknownIds(known, CLEAN_LEDGER)).isNull();

        // 条目溢出时被略去的 id 无从判真伪 ⇒ 不告警（也不因此转人工，见 gap 三态）
        String overflow = "[EVIDENCE_LEDGER] v1 totalChars=9000 entries=61 omitted=1\n"
                + "entry id=7 name=data.csv chars=9000 status=injected range=full shown=9000";
        ReviewVerdict unknown = verdict(false, coverage("not_verifiable", "id=99 name=ghost.csv"));
        assertThat(ReviewEvidenceUnavailability.unknownIds(unknown, overflow)).isNull();

        ReviewVerdict passed = verdict(true, coverage("not_verifiable", "id=99 name=ghost.csv"));
        assertThat(ReviewEvidenceUnavailability.unknownIds(passed, CLEAN_LEDGER)).isNull();
    }
}
