package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.service.SubTaskReviewService.ReviewVerdict;
import com.helloai.core.review.support.ReviewVerdictValidator.Finding;
import com.helloai.core.review.support.ReviewVerdictValidator.Severity;
import com.helloai.core.task.port.UncertaintyView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewCoverageCritic} 单测（`RV-3` 静态覆盖批评）。
 *
 * <p><b>锁定的判错方向</b>：批评器只把「通过」降级为「存疑转人工」（BLOCK），从不放行不合格交付；
 * {@code ASSUMPTION} 申报与 {@code pass=false} 判定一律不判（判前者是假阳性，判后者会丢掉明确结论）；
 * 验收标准为空时整体跳过（没有可核对对象）。</p>
 *
 * <p>覆盖实施计划 §3 `RV-3` 验收条款 ①②：圈号样例不误拆后的逐条归属、假 {@code criterionRef}
 * 判未覆盖、漏一条验收点即 BLOCK。</p>
 */
@DisplayName("ReviewCoverageCritic（RV-3 静态覆盖批评器）")
class ReviewCoverageCriticTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ITEM_ONE = "接口清单覆盖全部端点";
    private static final String ITEM_TWO = "每个端点标注请求参数";

    /** 圈号编号 + 条目内不含分隔符（真机最常见形态）。 */
    private static final String ACCEPTANCE = "① " + ITEM_ONE + "；② " + ITEM_TWO;

    private static final String COVERED_ONE =
            "{\"criterionRef\":\"" + ITEM_ONE + "\",\"status\":\"covered\",\"method\":\"attachment\","
                    + "\"evidenceRef\":\"api.md\"}";
    private static final String COVERED_TWO =
            "{\"criterionRef\":\"" + ITEM_TWO + "\",\"status\":\"covered\",\"method\":\"sandbox\","
                    + "\"evidenceRef\":\"沙箱台账#3\"}";

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("测试数据非法: " + raw, e);
        }
    }

    private static ReviewVerdict passed(String coverageJson) {
        ReviewVerdict verdict = new ReviewVerdict();
        verdict.setPass(true);
        verdict.setScore(4);
        verdict.setAnalysis("两条标准均有附件/沙箱证据");
        verdict.setIssues("");
        if (coverageJson != null) {
            verdict.setCoverage(json(coverageJson));
        }
        return verdict;
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream().map(Finding::code).toList();
    }

    private static Finding finding(List<Finding> findings, String code) {
        return findings.stream().filter(f -> f.code().equals(code)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("每条验收点都有合格覆盖：零 finding")
    void fullAdmissibleCoverageYieldsNoFindings() {
        List<Finding> findings = ReviewCoverageCritic.critique(
                passed("[" + COVERED_ONE + "," + COVERED_TWO + "]"), ACCEPTANCE, List.of());

        assertThat(findings).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("整段缺失 coverage：判 BLOCK（契约必填，缺失即无任何覆盖声明）")
    @ValueSource(strings = {"[]", "null", "{}"})
    void absentCoverageBlocks(String coverageJson) {
        List<Finding> findings = ReviewCoverageCritic.critique(passed(coverageJson), ACCEPTANCE, null);

        assertThat(codes(findings)).containsExactly(ReviewCoverageCritic.COVERAGE_ABSENT);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCK);
        assertThat(findings.get(0).detail()).contains("2 条验收标准");
    }

    @Test
    @DisplayName("漏一条验收点：BLOCK 未覆盖，detail 点名缺失条目")
    void uncoveredCriterionBlocks() {
        List<Finding> findings = ReviewCoverageCritic.critique(
                passed("[" + COVERED_ONE + "]"), ACCEPTANCE, List.of());

        assertThat(codes(findings)).containsExactly(ReviewCoverageCritic.ACCEPTANCE_NOT_COVERED);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCK);
        assertThat(findings.get(0).detail()).contains(ITEM_TWO).doesNotContain(ITEM_ONE);
    }

    @Test
    @DisplayName("假 criterionRef（近似措辞、非原文子串）：判未覆盖 + 引用不落地告警")
    void fabricatedCriterionRefIsNotCoverage() {
        String fake = "{\"criterionRef\":\"接口清单已覆盖全部端点\",\"status\":\"covered\",\"method\":\"attachment\"}";

        List<Finding> findings = ReviewCoverageCritic.critique(passed("[" + fake + "]"), ACCEPTANCE, null);

        assertThat(codes(findings)).containsExactlyInAnyOrder(
                ReviewCoverageCritic.ACCEPTANCE_NOT_COVERED,
                ReviewCoverageCritic.COVERAGE_REF_NOT_GROUNDED);
        // BLOCK 在前：调用方按 blockFindings() 决定 INCONCLUSIVE，顺序须稳定
        assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCK);
    }

    @Test
    @DisplayName("status=covered 但证据方式为产出文本自述：不计入覆盖并单列告警")
    void selfReportedEvidenceDoesNotCover() {
        String selfReportedOne = "{\"criterionRef\":\"" + ITEM_ONE + "\",\"status\":\"covered\","
                + "\"method\":\"text\",\"evidenceRef\":\"产出正文\"}";
        String selfReportedTwo = "{\"criterionRef\":\"" + ITEM_TWO + "\",\"status\":\"covered\","
                + "\"method\":\"text\"}";

        List<Finding> findings = ReviewCoverageCritic.critique(
                passed("[" + selfReportedOne + "," + selfReportedTwo + "]"), ACCEPTANCE, List.of());

        assertThat(codes(findings)).contains(
                ReviewCoverageCritic.ACCEPTANCE_NOT_COVERED,
                ReviewCoverageCritic.COVERAGE_SELF_REPORTED);
        assertThat(findings.get(0).code()).isEqualTo(ReviewCoverageCritic.ACCEPTANCE_NOT_COVERED);
        assertThat(finding(findings, ReviewCoverageCritic.COVERAGE_SELF_REPORTED).severity())
                .isEqualTo(Severity.WARN);
    }

    @Test
    @DisplayName("failed / not_verifiable 不算覆盖：仍需 BLOCK")
    void nonCoveredStatusLeavesCriterionUncovered() {
        String failedOne = "{\"criterionRef\":\"" + ITEM_ONE + "\",\"status\":\"failed\"}";

        List<Finding> findings = ReviewCoverageCritic.critique(
                passed("[" + failedOne + "," + COVERED_TWO + "]"), ACCEPTANCE, List.of());

        assertThat(codes(findings)).containsExactly(ReviewCoverageCritic.ACCEPTANCE_NOT_COVERED);
        assertThat(findings.get(0).detail()).contains(ITEM_ONE);
    }

    @Test
    @DisplayName("整条验收标准原文作为 criterionRef：双向归属，覆盖全部拆分项")
    void wholeAcceptanceRefCoversEveryItem() {
        String whole = "{\"criterionRef\":\"" + ACCEPTANCE + "\",\"status\":\"covered\","
                + "\"method\":\"attachment\"}";

        List<Finding> findings = ReviewCoverageCritic.critique(passed("[" + whole + "]"), ACCEPTANCE, null);

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("多余条目只告警不拦截：BLOCK 不在场")
    void extraneousEntryWarnsWithoutBlocking() {
        // 落地于 acceptance 原文但跨在两条拆分项边界上：归属成立、不指向任何拆分项
        String acceptance = ITEM_ONE + "；" + ITEM_TWO;
        String extraneous = "{\"criterionRef\":\"全部端点；每个\",\"status\":\"covered\",\"method\":\"attachment\"}";

        List<Finding> findings = ReviewCoverageCritic.critique(
                passed("[" + COVERED_ONE + "," + COVERED_TWO + "," + extraneous + "]"), acceptance, List.of());

        assertThat(codes(findings)).containsExactly(ReviewCoverageCritic.COVERAGE_EXTRANEOUS);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.WARN);
    }

    @Test
    @DisplayName("UNCONFIRMED 申报在判定里无声消失：BLOCK")
    void silencedUnconfirmedBlocks() {
        List<UncertaintyView> uncertainties = List.of(
                new UncertaintyView("UNCONFIRMED", "目标服务的鉴权方式未向业主确认", "U1"));

        List<Finding> findings = ReviewCoverageCritic.critique(
                passed("[" + COVERED_ONE + "," + COVERED_TWO + "]"), ACCEPTANCE, uncertainties);

        assertThat(codes(findings)).containsExactly(ReviewCoverageCritic.UNCONFIRMED_SILENTLY_DROPPED);
        assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCK);
        assertThat(findings.get(0).detail()).contains("U1");
    }

    @Test
    @DisplayName("判定以缺口标签 U1 说明处置：消音判据放过")
    void unconfirmedHandledByRefLabelPasses() {
        ReviewVerdict verdict = passed("[" + COVERED_ONE + "," + COVERED_TWO + "]");
        verdict.setAnalysis("U1 已由附件 auth-note.md 确认，鉴权为 Bearer");

        List<Finding> findings = ReviewCoverageCritic.critique(verdict, ACCEPTANCE,
                List.of(new UncertaintyView("UNCONFIRMED", "目标服务的鉴权方式未向业主确认", "U1")));

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("改写措辞但保留申报开头 12 字：锚点命中，放过")
    void unconfirmedHandledByNoteAnchorPasses() {
        // 申报原文与判定措辞刻意不同：只共享前 12 字锚点，验证「改写说法」不被判成消音
        String note = "鉴权方式尚未向业主确认，需运维查证后再判定";
        ReviewVerdict verdict = passed("[" + COVERED_ONE + "," + COVERED_TWO + "]");
        verdict.setComment("鉴权方式尚未向业主确认，其余已按附件答复处理完毕");

        List<Finding> findings = ReviewCoverageCritic.critique(verdict, ACCEPTANCE,
                List.of(new UncertaintyView("UNCONFIRMED", note)));

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("ASSUMPTION 申报不判消音（提示词第 10 条：不构成驳回理由）")
    void assumptionIsNeverJudged() {
        List<UncertaintyView> uncertainties = List.of(
                new UncertaintyView("ASSUMPTION", "假设部署环境为 Linux"),
                new UncertaintyView("TO_LOOK_UP", "需自行查证的项"));

        List<Finding> findings = ReviewCoverageCritic.critique(
                passed("[" + COVERED_ONE + "," + COVERED_TWO + "]"), ACCEPTANCE, uncertainties);

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("kind 非法值按 UNCONFIRMED 从严（fail-close 与 UncertaintyKind 同口径）")
    void unknownKindFailsCloseToUnconfirmed() {
        List<Finding> findings = ReviewCoverageCritic.critique(
                passed("[" + COVERED_ONE + "," + COVERED_TWO + "]"), ACCEPTANCE,
                List.of(new UncertaintyView("乱填的值", "该申报未被任何判定文本提及")));

        assertThat(codes(findings)).containsExactly(ReviewCoverageCritic.UNCONFIRMED_SILENTLY_DROPPED);
    }

    @Test
    @DisplayName("无 note 的申报不判消音（没有可核对的文本面，判即冤枉）")
    void blankNoteUncertaintyIsNotJudged() {
        List<Finding> findings = ReviewCoverageCritic.critique(
                passed("[" + COVERED_ONE + "," + COVERED_TWO + "]"), ACCEPTANCE,
                List.of(new UncertaintyView("UNCONFIRMED", "   ")));

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("驳回判定不判覆盖缺失：已有明确结论，不升级为存疑")
    void rejectedVerdictIsSkipped() {
        ReviewVerdict verdict = new ReviewVerdict();
        verdict.setPass(false);
        verdict.setScore(2);
        verdict.setIssues("[defect] 缺错误码表 [location] report.md [impact] 无法核对 [evidence] 正文无该节");

        assertThat(ReviewCoverageCritic.critique(verdict, ACCEPTANCE,
                List.of(new UncertaintyView("UNCONFIRMED", "未确认的缺口")))).isEmpty();
    }

    @Test
    @DisplayName("验收标准为空 / 判定为 null：整体跳过（不拿「平台没给标准」当「模型放水」）")
    void blankAcceptanceOrNullVerdictIsSkipped() {
        ReviewVerdict verdict = passed("[]");

        assertThat(ReviewCoverageCritic.critique(verdict, "", List.of())).isEmpty();
        assertThat(ReviewCoverageCritic.critique(verdict, "   ", List.of())).isEmpty();
        assertThat(ReviewCoverageCritic.critique(verdict, null, List.of())).isEmpty();
        assertThat(ReviewCoverageCritic.critique(null, ACCEPTANCE, List.of())).isEmpty();
    }

    @Test
    @DisplayName("多条未覆盖：detail 最多列 5 条并加省略号，不撑爆 payload")
    void manyUncoveredItemsAreCappedInDetail() {
        String acceptance = "一；二；三；四；五；六";
        String bogus = "{\"criterionRef\":\"不存在的标准\",\"status\":\"covered\",\"method\":\"attachment\"}";

        List<Finding> findings = ReviewCoverageCritic.critique(passed("[" + bogus + "]"), acceptance, null);

        Finding block = finding(findings, ReviewCoverageCritic.ACCEPTANCE_NOT_COVERED);
        assertThat(block).isNotNull();
        assertThat(block.detail()).contains("…").doesNotContain("六");
        assertThat(block.detail()).hasSizeLessThanOrEqualTo(203);
    }
}
