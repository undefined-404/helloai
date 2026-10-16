package com.helloai.core.review.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.service.SubTaskReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VerdictParser#parseVerdict} 判定解析单测。
 *
 * <p>重点覆盖 2026-10-16 事故修（严格 JSON 失败 → {@code looseVerdict} 宽松兜底）：审查模型已明文写出
 * {@code "pass": false} 时，不得因 JSON 格式瑕疵被整条判「不可解析」——报告审查侧对不可解析的处置是
 * {@code convergeToDone}（放过），等价于把明确驳回静默改成通过。</p>
 */
@DisplayName("VerdictParser.parseVerdict")
class VerdictParserTest {

    private final VerdictParser parser = new VerdictParser(new ObjectMapper());

    @Test
    @DisplayName("严格 JSON（直接可解析）→ 正常解出 pass/score/issues")
    void strictJson() {
        SubTaskReviewService.ReviewVerdict verdict = parser.parseVerdict(
                "{\"pass\": true, \"score\": 5, \"issues\": \"无\", \"comment\": \"很好\"}");
        assertThat(verdict).isNotNull();
        assertThat(verdict.getPass()).isTrue();
        assertThat(verdict.getScore()).isEqualTo(5);
        assertThat(verdict.getIssues()).isEqualTo("无");
    }

    @Test
    @DisplayName("★事故形态：脏文本（无 JSON 花括号）但明文含 \"pass\": false, \"score\": 2 → 兜底仍判驳回（不得静默放过）")
    void looseFallbackKeepsExplicitReject() {
        // 无合法 JSON 对象 ⇒ 严格解析必失败；但判定字段明文可见 ⇒ 兜底须解出驳回
        SubTaskReviewService.ReviewVerdict verdict = parser.parseVerdict(
                "以下是核验结论（非 JSON）：\n\"pass\": false, \"score\": 2, \"issues\": \"报告正文缺失\"\n"
                        + "VERIFICATION:\n对照要求核实…STATUS: DONE");
        assertThat(verdict).isNotNull();
        assertThat(verdict.getPass()).isFalse();
        assertThat(verdict.getScore()).isEqualTo(2);
        assertThat(verdict.getIssues()).isEqualTo("报告正文缺失");
    }

    @Test
    @DisplayName("宽松兜底：大写/空白变体 PASS 也识别（大小写不敏感）")
    void looseFallbackCaseInsensitive() {
        SubTaskReviewService.ReviewVerdict verdict = parser.parseVerdict(
                "判定： \"PASS\" : TRUE  （额外叙述导致 JSON 不闭合");
        assertThat(verdict).isNotNull();
        assertThat(verdict.getPass()).isTrue();
    }

    @Test
    @DisplayName("宽松兜底：只有 pass 无 score/issues → 解出 pass、其余为 null（不猜）")
    void looseFallbackPassOnly() {
        SubTaskReviewService.ReviewVerdict verdict = parser.parseVerdict(
                "{\"pass\": false} trailing junk ###");
        assertThat(verdict).isNotNull();
        assertThat(verdict.getPass()).isFalse();
        assertThat(verdict.getScore()).isNull();
    }

    @Test
    @DisplayName("取不到 pass → 仍返 null（不猜；调用方据此记不可解析）")
    void noPassStillNull() {
        assertThat(parser.parseVerdict("完全读不懂的一段文字，无任何判定字段")).isNull();
        // 有 score 无 pass：兜底不臆造判定
        assertThat(parser.parseVerdict("{\"score\": 3, \"issues\": \"x\" }###broken")).isNull();
    }

    @Test
    @DisplayName("null / 空白 → null")
    void blankInput() {
        assertThat(parser.parseVerdict(null)).isNull();
        assertThat(parser.parseVerdict("   ")).isNull();
    }
}
