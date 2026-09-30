package com.helloai.core.review.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ReviewFailureSignature 单测（P-1 防御层 A2-2）：
 * 重复失败相似的判定边界 —— 同一批驳回意见（含格式漂移）≥ 阈值、不同拒因 < 阈值。
 */
@DisplayName("ReviewFailureSignature 重复失败签名")
class ReviewFailureSignatureTest {

    /** 与 AgentDispatchProperties.autoReviewRepeatFailureSimilarity 的默认阈值一致。 */
    private static final double DEFAULT_THRESHOLD = 0.85;

    @Test
    @DisplayName("完全相同的驳回意见 → 1.0")
    void shouldReturnOneForIdenticalIssues() {
        String issues = "7 条关键词中仅 2 条出现在材料中，其余 5 条 not contained";
        assertThat(ReviewFailureSignature.similarity(issues, issues)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("分隔符/大小写漂移（K3/K4 vs K3、K4）→ 规范化后仍 1.0")
    void shouldBeInsensitiveToPunctuationDrift() {
        assertThat(ReviewFailureSignature.similarity(
                "缺 3 个端点（K3/K4/K5）",
                "缺 3 个端点（K3、K4、K5）")).isEqualTo(1.0);
        assertThat(ReviewFailureSignature.similarity(
                "Missing Endpoint: K3",
                "missing endpoint: k3")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("近乎相同（仅尾部追加一句）→ 仍 ≥ 阈值")
    void shouldStayAboveThresholdForNearlyIdenticalIssues() {
        String prev = "7 条关键词中仅 2 条出现在材料中，其余 5 条 not contained in the material，无法完成核验";
        String curr = "7 条关键词中仅 2 条出现在材料中，其余 5 条 not contained in the material";
        assertThat(ReviewFailureSignature.similarity(prev, curr))
                .isGreaterThanOrEqualTo(DEFAULT_THRESHOLD);
    }

    @Test
    @DisplayName("不同拒因（缺端点 vs 格式不对）→ 显著低于阈值")
    void shouldFallBelowThresholdForDifferentIssues() {
        assertThat(ReviewFailureSignature.similarity("缺端点", "格式不对"))
                .isEqualTo(0.0);
        assertThat(ReviewFailureSignature.similarity(
                "缺少订单过期接口的幂等性处理",
                "文档格式不符，缺少目录结构说明"))
                .isLessThan(DEFAULT_THRESHOLD);
    }

    @Test
    @DisplayName("双方均为空 → 1.0（同为无意见）；单侧为空 → 0.0（无法判同）")
    void shouldHandleEmptyInputs() {
        assertThat(ReviewFailureSignature.similarity(null, null)).isEqualTo(1.0);
        assertThat(ReviewFailureSignature.similarity("，。", " ")).isEqualTo(1.0);
        assertThat(ReviewFailureSignature.similarity(null, "缺端点")).isEqualTo(0.0);
        assertThat(ReviewFailureSignature.similarity("缺端点", "。")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("单字符文本 → 以自身为唯一边可比")
    void shouldHandleSingleCharText() {
        assertThat(ReviewFailureSignature.similarity("缺", "缺")).isEqualTo(1.0);
        assertThat(ReviewFailureSignature.similarity("缺", "错")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("normalize：null → 空串；List → 「；」连接；其余 toString")
    void shouldNormalizeHistoryIssuesValue() {
        assertThat(ReviewFailureSignature.normalize(null)).isEmpty();
        assertThat(ReviewFailureSignature.normalize(List.of("缺端点", "缺分页")))
                .isEqualTo("缺端点；缺分页");
        assertThat(ReviewFailureSignature.normalize(42)).isEqualTo("42");
        assertThat(ReviewFailureSignature.normalize("原样文本")).isEqualTo("原样文本");
    }

    @Test
    @DisplayName("asScore：Number 直取；数字字符串解析；其余 null")
    void shouldNormalizeHistoryScoreValue() {
        assertThat(ReviewFailureSignature.asScore(2)).isEqualTo(2);
        assertThat(ReviewFailureSignature.asScore(2.0d)).isEqualTo(2);
        assertThat(ReviewFailureSignature.asScore("3")).isEqualTo(3);
        assertThat(ReviewFailureSignature.asScore(" 4 ")).isEqualTo(4);
        assertThat(ReviewFailureSignature.asScore("abc")).isNull();
        assertThat(ReviewFailureSignature.asScore(null)).isNull();
    }

    // ══════════════════════════════════════════════════════════════
    //  真实语料回归锚点（R1 修订，2026-09-30 审计 §15.2 独立复现）
    //  tku-e2e-01 sub3 三轮真实驳回意见（2753/1526/928 字符）：同一批缺失项
    //  每轮以不同措辞与编号（P0+A1~A3+B1~B3 → call 1/2 → R3~R7）整篇重写，
    //  相似度实测仅 0.18~0.26，全部低于阈值 0.85——证明相似度不可作为短路
    //  主判据（主判据已改为「两轮 score 未严格提升」，见 SubTaskReviewService）。
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("真实语料锚点：sub3 三轮驳回意见相似度实测 0.18~0.26 全低于 0.85（旧判据失效实证）")
    void shouldMatchRealCorpusAnchorsBelowThreshold() throws Exception {
        String r1 = loadCorpus("tku-e2e-01-sub3-r1.txt");
        String r2 = loadCorpus("tku-e2e-01-sub3-r2.txt");
        String r3 = loadCorpus("tku-e2e-01-sub3-r3.txt");
        // 逐字符快照（.gitattributes -text 保证无换行转换，见 review-corpus 目录说明）
        assertThat(r1).hasSize(2753);
        assertThat(r2).hasSize(1526);
        assertThat(r3).hasSize(928);

        double sim12 = ReviewFailureSignature.similarity(r1, r2);
        double sim23 = ReviewFailureSignature.similarity(r2, r3);
        double sim13 = ReviewFailureSignature.similarity(r1, r3);

        // 锚点值来自 2026-09-30 审计复核 + Python 独立复现（与 Java 实现逐位一致）
        assertThat(sim12).isCloseTo(0.2537, within(0.005));
        assertThat(sim23).isCloseTo(0.1927, within(0.005));
        assertThat(sim13).isCloseTo(0.1799, within(0.005));
        assertThat(sim12).isLessThan(DEFAULT_THRESHOLD);
        assertThat(sim23).isLessThan(DEFAULT_THRESHOLD);
        assertThat(sim13).isLessThan(DEFAULT_THRESHOLD);
    }

    /** 读取 review-corpus 真实语料夹具（测试资源，UTF-8 原文）。 */
    private static String loadCorpus(String name) throws Exception {
        try (var in = ReviewFailureSignatureTest.class.getResourceAsStream("/review-corpus/" + name)) {
            assertThat(in).as("语料夹具缺失: %s", name).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
