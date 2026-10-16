package com.helloai.core.review.quality;

import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.core.agent.quality.gate.GateDecision;
import com.helloai.core.agent.quality.gate.GateOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@link RepeatedFailureGate} 单测（RM12 收口切片 + `LOG-20261011-007` B 增补切片）。
 *
 * <p>锁定两组判据：① R1 二次修订后的双条件判据 {@code scoreStalled && textRepeated}
 * （「评分停滞但问题已变 → 不短路」是真机 687 连续两次误伤的回归防线）；
 * ② 先判的「执行者自报未完成连续 N 轮」（basis={@code self_reported_stall}，
 * 专治真停滞形态的盲区）。</p>
 */
class RepeatedFailureGateTest {

    private static final double THRESHOLD = 0.85;

    /** 自报停滞判据的默认轮数（与 AgentDispatchProperties 默认值一致）。 */
    private static final int STALL_ROUNDS = 3;

    private RepeatedFailureGate gate;

    @BeforeEach
    void setUp() {
        AgentDispatchProperties props = mock(AgentDispatchProperties.class);
        lenient().when(props.getAutoReviewRepeatFailureSimilarity()).thenReturn(THRESHOLD);
        // B 自报停滞判据默认开启（与生产默认一致）
        lenient().when(props.isAutoReviewSelfStallEnabled()).thenReturn(true);
        lenient().when(props.getAutoReviewSelfStallRounds()).thenReturn(STALL_ROUNDS);
        gate = new RepeatedFailureGate(props);
    }

    private static Map<String, Object> round(int no, Object score, String issues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", no);
        m.put("score", score);
        m.put("issues", issues);
        return m;
    }

    /**
     * 带执行者自报状态的一轮。
     *
     * <p>issues 刻意<b>逐轮不同</b>——用于证明「自报停滞」判据完全不依赖驳回意见的文本相似度
     * （该路已被 §15.3 真实语料实测证伪）。</p>
     */
    private static Map<String, Object> stalledRound(int no, Object score, String status) {
        Map<String, Object> m = round(no, score, "第 " + no + " 轮驳回意见（措辞与编号均已重写，" + no + "）");
        m.put("executorStatus", status);
        return m;
    }

    @Test
    @DisplayName("闸门标识")
    void gateName() {
        assertEquals("repeated_failure_signature", gate.name());
    }

    @Test
    @DisplayName("history 不足两条 → SKIP")
    void insufficientHistorySkips() {
        assertEquals(GateOutcome.SKIP, gate.evaluate(null).outcome());
        assertEquals(GateOutcome.SKIP, gate.evaluate(List.of(round(1, 2, "a"))).outcome());
    }

    @Test
    @DisplayName("任一 issues 为空 → PASS（先决条件不满足，维持原行为）")
    void blankIssuesPass() {
        assertEquals(GateOutcome.PASS,
                gate.evaluate(List.of(round(1, 2, ""), round(2, 2, "b"))).outcome());
    }

    @Test
    @DisplayName("评分停滞 + 文本高度相似 → BLOCK，evidence 带 basis/scoreTrend/threshold")
    void stalledAndRepeatedBlocks() {
        String a = "契约缺少错误码定义，接口未物化，附录缺失";
        GateDecision d = gate.evaluate(List.of(round(1, 2, a), round(2, 2, a)));
        assertEquals(GateOutcome.BLOCK, d.outcome());
        assertTrue(d.blocks());
        assertEquals("score_stall_and_text_repeat", d.evidence("basis"));
        assertEquals("2->2", d.evidence("scoreTrend"));
        assertEquals(THRESHOLD, (double) d.evidence("threshold"), 1e-9);
        assertTrue(d.hasSeverity(com.helloai.core.agent.quality.gate.GateSeverity.HARD));
    }

    @Test
    @DisplayName("评分停滞但问题已变（低相似度）→ PASS（真机 687 误伤回归防线）")
    void stalledButChangedPasses() {
        GateDecision d = gate.evaluate(List.of(
                round(6, 2, "附件被截断导致 store.py 自洽矛盾"),
                round(8, 2, "契约尚未物化，缺少部署脚本与回滚步骤")));
        assertEquals(GateOutcome.PASS, d.outcome());
        assertFalse(d.blocks());
    }

    @Test
    @DisplayName("评分提升 → PASS")
    void improvedScorePasses() {
        String a = "契约缺少错误码定义";
        assertEquals(GateOutcome.PASS, gate.evaluate(List.of(round(1, 2, a), round(2, 4, a))).outcome());
    }

    @Test
    @DisplayName("评分不可比 → 退回文本重复兜底（basis=text_repeat，scoreTrend=n/a）")
    void notComparableFallsBackToText() {
        String a = "同一批缺失项：错误码、附录、回滚步骤";
        GateDecision d = gate.evaluate(List.of(round(1, null, a), round(2, "x", a)));
        assertEquals(GateOutcome.BLOCK, d.outcome());
        assertEquals("text_repeat", d.evidence("basis"));
        assertEquals("n/a", d.evidence("scoreTrend"));
    }

    // ══════════════════════════════════════════════════════════════
    //  LOG-20261011-007 B 收敛熔断：执行者自报未完成连续 N 轮
    //  （signal = 各轮轮报 STATUS；不依赖任何文本相似度）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("B：连续 3 轮自报未完成 + 评分不提升 → BLOCK（basis=self_reported_stall + 归因标注）")
    void selfReportedStallBlocks() {
        GateDecision d = gate.evaluate(List.of(
                stalledRound(1, 2, "PARTIAL"),
                stalledRound(2, 2, "PARTIAL"),
                stalledRound(3, 2, "BLOCKED")));
        assertEquals(GateOutcome.BLOCK, d.outcome());
        assertTrue(d.blocks());
        assertEquals("self_reported_stall", d.evidence("basis"));
        assertEquals("2->2", d.evidence("scoreTrend"));
        assertEquals(3, d.evidence("rounds"));
        assertEquals("PARTIAL/PARTIAL/BLOCKED", d.evidence("executorStatuses"));
        // 归因标注：连续自认未完成 ⇒ 高概率「执行者无能力满足 / 输入缺失」
        assertEquals("executor_incapable_or_input_missing", d.evidence("annotation"));
        assertTrue(d.hasSeverity(com.helloai.core.agent.quality.gate.GateSeverity.HARD));
    }

    @Test
    @DisplayName("B：状态取值大小写不敏感（小写 partial / blocked 亦识别）")
    void selfReportedStallIsCaseInsensitive() {
        GateDecision d = gate.evaluate(List.of(
                stalledRound(1, 2, "partial"),
                stalledRound(2, 2, "blocked"),
                stalledRound(3, 2, " Blocked ")));
        assertEquals(GateOutcome.BLOCK, d.outcome());
        assertEquals("PARTIAL/BLOCKED/BLOCKED", d.evidence("executorStatuses"));
    }

    @Test
    @DisplayName("B：连续自报未完成但评分提升 → PASS（有进展不提前熔断，防误伤收敛中的返工）")
    void selfReportedStallWithScoreImprovementPasses() {
        assertEquals(GateOutcome.PASS, gate.evaluate(List.of(
                stalledRound(1, 1, "PARTIAL"),
                stalledRound(2, 2, "PARTIAL"),
                stalledRound(3, 3, "PARTIAL"))).outcome());
    }

    @Test
    @DisplayName("B：窗口内出现一轮自报 DONE → PASS（不构成「连续未完成」）")
    void doneStatusBreaksStall() {
        assertEquals(GateOutcome.PASS, gate.evaluate(List.of(
                stalledRound(1, 2, "PARTIAL"),
                stalledRound(2, 2, "DONE"),
                stalledRound(3, 2, "PARTIAL"))).outcome());
    }

    @Test
    @DisplayName("B：任一状态缺失 → 判据不适用，落回原两轮判据（不误伤无围栏的外部执行者）")
    void missingStatusMakesCriterionInapplicable() {
        // 第 2 轮无 executorStatus（外部 CLI_CLIENT 执行者不产出围栏）⇒ 整条判据不适用；
        // 原两轮判据亦不命中（issues 逐轮不同）⇒ PASS
        assertEquals(GateOutcome.PASS, gate.evaluate(List.of(
                stalledRound(1, 2, "PARTIAL"),
                round(2, 2, "另一批全新问题，措辞完全不同"),
                stalledRound(3, 2, "PARTIAL"))).outcome());
    }

    @Test
    @DisplayName("B：历史轮数不足 N=3 → 落回原两轮判据（N 轮门槛生效）")
    void stallNeedsEnoughRounds() {
        assertEquals(GateOutcome.PASS, gate.evaluate(List.of(
                stalledRound(1, 2, "PARTIAL"),
                stalledRound(2, 2, "PARTIAL"))).outcome());
    }

    @Test
    @DisplayName("B：开关关闭 → 判据不生效，落回原两轮判据")
    void stallDisabledFallsBack() {
        AgentDispatchProperties props = mock(AgentDispatchProperties.class);
        lenient().when(props.getAutoReviewRepeatFailureSimilarity()).thenReturn(THRESHOLD);
        lenient().when(props.isAutoReviewSelfStallEnabled()).thenReturn(false);
        RepeatedFailureGate disabled = new RepeatedFailureGate(props);

        assertEquals(GateOutcome.PASS, disabled.evaluate(List.of(
                stalledRound(1, 2, "PARTIAL"),
                stalledRound(2, 2, "BLOCKED"),
                stalledRound(3, 2, "PARTIAL"))).outcome());
    }

    @Test
    @DisplayName("B：评分为 n/a 时也判（自报停滞不依赖评分可比性，只要求「未提升」）")
    void selfReportedStallWithMissingScoreBlocks() {
        GateDecision d = gate.evaluate(List.of(
                stalledRound(1, null, "BLOCKED"),
                stalledRound(2, null, "BLOCKED"),
                stalledRound(3, null, "BLOCKED")));
        assertEquals(GateOutcome.BLOCK, d.outcome());
        assertEquals("n/a", d.evidence("scoreTrend"));
    }
}
