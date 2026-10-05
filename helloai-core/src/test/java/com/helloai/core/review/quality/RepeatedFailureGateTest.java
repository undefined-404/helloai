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
 * {@link RepeatedFailureGate} 单测（RM12 收口切片）。
 *
 * <p>锁定 R1 二次修订后的双条件判据：{@code scoreStalled && textRepeated}。
 * 其中「评分停滞但问题已变 → 不短路」是 <b>真机 687 连续两次误伤</b>的回归防线。</p>
 */
class RepeatedFailureGateTest {

    private static final double THRESHOLD = 0.85;

    private RepeatedFailureGate gate;

    @BeforeEach
    void setUp() {
        AgentDispatchProperties props = mock(AgentDispatchProperties.class);
        lenient().when(props.getAutoReviewRepeatFailureSimilarity()).thenReturn(THRESHOLD);
        gate = new RepeatedFailureGate(props);
    }

    private static Map<String, Object> round(int no, Object score, String issues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", no);
        m.put("score", score);
        m.put("issues", issues);
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
}
