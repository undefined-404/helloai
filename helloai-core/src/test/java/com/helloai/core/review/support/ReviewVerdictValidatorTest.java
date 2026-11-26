package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.service.SubTaskReviewService.ReviewVerdict;
import com.helloai.core.review.support.ReviewVerdictValidator.Finding;
import com.helloai.core.review.support.ReviewVerdictValidator.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewVerdictValidator} 表驱动单测（`RV-1`）。
 *
 * <p>覆盖：{@code pass × score} 四象限、{@code analysis} 空值、宽松解析标记、
 * 驳回缺 {@code [defect]}、{@code acceptanceRef} 假子串、空验收标准、{@code missingEvidence}
 * 非法形态、多条聚合与 detail 截断。</p>
 *
 * <p><b>范围声明</b>：{@code pass=true && score<3} 由既有 {@code MIN_PASS_SCORE} 拦截，
 * 校验器不重复标注（用例 ③ 显式固化该边界）。</p>
 */
@DisplayName("ReviewVerdictValidator（RV-1 静态判定校验）")
class ReviewVerdictValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ACCEPTANCE = "① 接口清单覆盖全部端点；② 每个端点标注请求参数";

    private static final String GROUNDED_EVIDENCE =
            "[{\"acceptanceRef\":\"① 接口清单覆盖全部端点\",\"missing\":\"缺 GET /x\",\"howTo\":\"补文档\"}]";

    /** 与验收原文「近似但不相等」的引用：不是子串 ⇒ 必须被标注。 */
    private static final String FAKE_EVIDENCE =
            "[{\"acceptanceRef\":\"接口清单已覆盖全部端点\"}]";

    private static ReviewVerdict verdict(Boolean pass, Integer score, String analysis,
                                         String issues, String missingEvidenceJson) {
        ReviewVerdict verdict = new ReviewVerdict();
        verdict.setPass(pass);
        verdict.setScore(score);
        verdict.setAnalysis(analysis);
        verdict.setIssues(issues);
        if (missingEvidenceJson != null) {
            try {
                verdict.setMissingEvidence(MAPPER.readTree(missingEvidenceJson));
            } catch (Exception e) {
                throw new IllegalStateException("测试数据非法: " + missingEvidenceJson, e);
            }
        }
        return verdict;
    }

    private static Arguments c(String name, ReviewVerdict verdict, String acceptance, boolean looseParse,
                               String... expectedCodes) {
        return Arguments.of(name, verdict, acceptance, looseParse, List.of(expectedCodes));
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // ===== pass × score 四象限 =====
                c("① pass=true score=5 无瑕疵", verdict(true, 5, "逐条对照通过", null, null), ACCEPTANCE, false),
                c("② pass=true score=3（及格线）无瑕疵", verdict(true, 3, "逐条对照通过", null, null), ACCEPTANCE, false),
                c("③ pass=true score=2：既有 MIN_PASS_SCORE 拦截，校验器不重复标注",
                        verdict(true, 2, "逐条对照通过", null, null), ACCEPTANCE, false),
                c("④ pass=true score=null（缺评分不算矛盾）", verdict(true, null, "逐条对照通过", null, null), ACCEPTANCE, false),
                c("⑤ pass=false score=1 + [defect]", verdict(false, 1, "逐条核对", "[defect] 缺端点", null), ACCEPTANCE, false),
                c("⑥ pass=false score=4（保守驳回不拦）", verdict(false, 4, "逐条核对", "[defect] 严重不足", null), ACCEPTANCE, false),
                c("⑦ pass=false score=5 → 逻辑矛盾（BLOCK）", verdict(false, 5, "逐条核对", "[defect] 无输出", null),
                        ACCEPTANCE, false, ReviewVerdictValidator.REJECT_WITH_FULL_SCORE),
                c("⑧ pass=false score=null + [defect]", verdict(false, null, "逐条核对", "[defect] 缺证据", null), ACCEPTANCE, false),

                // ===== analysis =====
                c("⑨ analysis 缺失（null）", verdict(true, 5, null, null, null), ACCEPTANCE, false,
                        ReviewVerdictValidator.ANALYSIS_MISSING),
                c("⑩ analysis 空白（全空格）", verdict(true, 5, "   ", null, null), ACCEPTANCE, false,
                        ReviewVerdictValidator.ANALYSIS_MISSING),

                // ===== 驳回缺缺陷条目 =====
                c("⑪ 驳回但 issues 无 [defect]", verdict(false, 1, "逐条核对", "内容太差", null), ACCEPTANCE, false,
                        ReviewVerdictValidator.REJECT_WITHOUT_DEFECT),
                c("⑫ 驳回但 issues 为空", verdict(false, 1, "逐条核对", "", null), ACCEPTANCE, false,
                        ReviewVerdictValidator.REJECT_WITHOUT_DEFECT),
                c("⑬ 驳回 + analysis 缺失 + 无 defect → 两条", verdict(false, 1, null, "内容太差", null), ACCEPTANCE, false,
                        ReviewVerdictValidator.ANALYSIS_MISSING, ReviewVerdictValidator.REJECT_WITHOUT_DEFECT),

                // ===== 宽松解析标记 =====
                c("⑭ 宽松兜底命中", verdict(true, 4, "逐条核对", null, null), ACCEPTANCE, true,
                        ReviewVerdictValidator.LOOSE_PARSE),
                c("⑮ 严格解析（loose=false）不标", verdict(true, 4, "逐条核对", null, null), ACCEPTANCE, false),

                // ===== acceptanceRef 归属 =====
                c("⑯ acceptanceRef 是验收原文子串", verdict(false, 2, "逐条核对", "[defect] 缺 GET /x", GROUNDED_EVIDENCE),
                        ACCEPTANCE, false),
                c("⑰ acceptanceRef 假子串（近似但非原文）", verdict(false, 2, "逐条核对", "[defect] 缺 GET /x", FAKE_EVIDENCE),
                        ACCEPTANCE, false, ReviewVerdictValidator.ACCEPTANCE_REF_NOT_GROUNDED),
                c("⑱ acceptanceRef 首尾空白 trim 后命中",
                        verdict(false, 2, "逐条核对", "[defect] 缺 GET /x",
                                "[{\"acceptanceRef\":\"  ① 接口清单覆盖全部端点  \"}]"),
                        ACCEPTANCE, false),
                c("⑲ 验收标准为空 ⇒ 跳过归属校验", verdict(false, 2, "逐条核对", "[defect] 缺 GET /x", FAKE_EVIDENCE),
                        "  ", false),
                c("⑳ missingEvidence 非数组 ⇒ 跳过", verdict(false, 2, "逐条核对", "[defect] 缺 GET /x", "{\"a\":1}"),
                        ACCEPTANCE, false),
                c("㉑ missingEvidence 空数组", verdict(false, 2, "逐条核对", "[defect] 缺 GET /x", "[]"), ACCEPTANCE, false),
                c("㉒ 多条不实引用聚合为一条 finding",
                        verdict(false, 2, "逐条核对", "[defect] 缺证据",
                                "[{\"acceptanceRef\":\"a\"},{\"acceptanceRef\":\"b\"}]"),
                        ACCEPTANCE, false, ReviewVerdictValidator.ACCEPTANCE_REF_NOT_GROUNDED),

                // ===== 组合 =====
                c("㉓ 四项 Warn 同时命中",
                        verdict(false, 1, null, "内容太差", FAKE_EVIDENCE), ACCEPTANCE, true,
                        ReviewVerdictValidator.ANALYSIS_MISSING, ReviewVerdictValidator.LOOSE_PARSE,
                        ReviewVerdictValidator.REJECT_WITHOUT_DEFECT,
                        ReviewVerdictValidator.ACCEPTANCE_REF_NOT_GROUNDED),
                c("㉔ Block + Warn 同时命中", verdict(false, 5, null, "[defect] 无输出", null), ACCEPTANCE, false,
                        ReviewVerdictValidator.REJECT_WITH_FULL_SCORE, ReviewVerdictValidator.ANALYSIS_MISSING),

                // ===== `G-052` 归一档：格式差异计入归属但单列观测 =====
                c("㉕ acceptanceRef 仅剥反引号才命中 ⇒ 记格式观测，不记不实",
                        verdict(false, 2, "逐条核对", "[defect] 缺证据",
                                "[{\"acceptanceRef\":\"主色 #RRGGBB 大写六位\"}]"),
                        "③ 主色 `#RRGGBB` 大写六位", false, ReviewVerdictValidator.REF_FORMAT_LOOSE),
                c("㉖ acceptanceRef 与原文只差中间一段加粗标记 ⇒ 记格式观测",
                        verdict(false, 2, "逐条核对", "[defect] 缺证据",
                                "[{\"acceptanceRef\":\"退出码必须为 0 且标准输出非空\"}]"),
                        "④ 退出码**必须为 0 且标准输出非空**", false, ReviewVerdictValidator.REF_FORMAT_LOOSE),
                c("㉗ 空壳短引用（归一后仅剩圈号）不放行",
                        verdict(false, 2, "逐条核对", "[defect] 缺证据", "[{\"acceptanceRef\":\"**①**\"}]"),
                        ACCEPTANCE, false, ReviewVerdictValidator.ACCEPTANCE_REF_NOT_GROUNDED)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("表驱动：判定 → 期望 finding 代码集合")
    void validateTable(String name, ReviewVerdict verdict, String acceptance, boolean looseParse,
                       List<String> expectedCodes) {
        List<Finding> findings = ReviewVerdictValidator.validate(verdict, acceptance, looseParse);

        assertThat(findings).extracting(Finding::code).containsExactlyInAnyOrderElementsOf(expectedCodes);
    }

    @Test
    @DisplayName("verdict 为 null ⇒ 空清单（不可解析由调用方另行处置）")
    void nullVerdictShouldYieldNothing() {
        assertThat(ReviewVerdictValidator.validate(null, ACCEPTANCE, true)).isEmpty();
    }

    @Test
    @DisplayName("`G-052` 归属判据：容纳排版差异，不容纳内容改写")
    void groundingShouldTolerateFormattingButNotRewriting() {
        String source = "① 主色 `#RRGGBB` 大写六位；② 退出码必须为 0";

        // 字面命中：两级判据同时成立
        assertThat(ReviewVerdictValidator.isGroundedIn(source, "主色 `#RRGGBB` 大写六位")).isTrue();
        assertThat(ReviewVerdictValidator.isGroundedExactly(source, "主色 `#RRGGBB` 大写六位")).isTrue();

        // 只丢了反引号：仍归属，但不再是字面命中（这条差异就是 `G-052` 真机事故）
        assertThat(ReviewVerdictValidator.isGroundedIn(source, "主色 #RRGGBB 大写六位")).isTrue();
        assertThat(ReviewVerdictValidator.isGroundedExactly(source, "主色 #RRGGBB 大写六位")).isFalse();

        // 改了内容：两级判据都不成立（归一只削排版，不削文字）
        assertThat(ReviewVerdictValidator.isGroundedIn(source, "主色 #RRGGBB 小写六位")).isFalse();

        // 空壳短引用：不许靠归一放行（"①" 单独出现本身是原文子串，故用只在归一后才成立的 "**①**" 反证）
        assertThat(ReviewVerdictValidator.isGroundedIn(source, "**①**")).isFalse();
        assertThat(ReviewVerdictValidator.isGroundedIn(source, "①")).isTrue();

        // 空白折叠：换行 + 连续空格等价于单空格（同样只是格式差异）
        assertThat(ReviewVerdictValidator.isGroundedIn("退出码   必须为\n0", "退出码 必须为 0")).isTrue();
        assertThat(ReviewVerdictValidator.isGroundedExactly("退出码   必须为\n0", "退出码 必须为 0")).isFalse();
    }

    @Test
    @DisplayName("分级：BLOCK 只属于「驳回 + 满分」，其余均为 WARN")
    void severityShouldFollowClassification() {
        List<Finding> findings = ReviewVerdictValidator.validate(
                verdict(false, 5, null, "内容太差", null), ACCEPTANCE, true);

        assertThat(findings).filteredOn(f -> f.severity() == Severity.BLOCK)
                .extracting(Finding::code)
                .containsExactly(ReviewVerdictValidator.REJECT_WITH_FULL_SCORE);
        assertThat(findings).filteredOn(f -> f.severity() == Severity.WARN)
                .extracting(Finding::code)
                .containsExactlyInAnyOrder(ReviewVerdictValidator.ANALYSIS_MISSING,
                        ReviewVerdictValidator.LOOSE_PARSE, ReviewVerdictValidator.REJECT_WITHOUT_DEFECT);
    }

    @Test
    @DisplayName("detail 截断：超长 issues 不撑大 payload（≤203 字符，带省略号）")
    void detailShouldBeBounded() {
        List<Finding> findings = ReviewVerdictValidator.validate(
                verdict(false, 1, "逐条核对", "x".repeat(500), null), ACCEPTANCE, false);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).detail()).hasSizeLessThanOrEqualTo(203).endsWith("...");
    }
}
