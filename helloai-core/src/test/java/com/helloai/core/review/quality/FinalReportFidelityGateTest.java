package com.helloai.core.review.quality;

import com.helloai.core.agent.quality.gate.GateDecision;
import com.helloai.core.agent.quality.gate.GateOutcome;
import com.helloai.core.agent.quality.gate.GateSeverity;
import com.helloai.core.review.support.FinalReportFidelityChecker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FinalReportFidelityGate} 单测（RM12 收口切片）。
 *
 * <p>核心断言是<b>行为等价</b>：闸门结论与被其持有的纯静态
 * {@link FinalReportFidelityChecker} 逐项一致（{@code blocks() ↔ hasHard()}，
 * 渲染文本逐字相同）——这是本切片「零行为变更」的机器证据。</p>
 */
class FinalReportFidelityGateTest {

    private final FinalReportFidelityGate gate = new FinalReportFidelityGate();

    /** 空壳引用（HARD：把契约性事实外置）。 */
    private static final String HARD_SHELL = "# 整合报告\n## 1. 开源矩阵\n完整 11×8 矩阵取值见分册第4章。\n";
    /** 仅软违规（缺「覆盖追溯表」）。 */
    private static final String SOFT_ONLY = "# 报告\n## 1. 结论\n结论正文。\n";
    /** 无违规（含覆盖追溯表、围栏平衡、无空壳引用、无跨章重复）。 */
    private static final String CLEAN = "# 报告\n## 覆盖追溯表\n| 章节 | 子任务 |\n## 2. 结论\n结论正文。\n";

    @Test
    @DisplayName("闸门标识")
    void gateName() {
        assertEquals("final_report_fidelity", gate.name());
        assertEquals(FinalReportFidelityGate.GATE_NAME, gate.name());
    }

    @Test
    @DisplayName("空报告 → HARD(empty_report) → BLOCK")
    void emptyReportBlocks() {
        GateDecision d = gate.evaluate("");
        assertEquals(GateOutcome.BLOCK, d.outcome());
        assertTrue(d.hasSeverity(GateSeverity.HARD));
    }

    @Test
    @DisplayName("空壳引用 → HARD → BLOCK")
    void shellReferenceBlocks() {
        assertEquals(GateOutcome.BLOCK, gate.evaluate(HARD_SHELL).outcome());
    }

    @Test
    @DisplayName("仅软违规（缺覆盖追溯表）→ ADVISE，不拦截")
    void softOnlyAdvises() {
        GateDecision d = gate.evaluate(SOFT_ONLY);
        assertEquals(GateOutcome.ADVISE, d.outcome());
        assertFalse(d.blocks());
        assertTrue(d.hasSeverity(GateSeverity.SOFT));
    }

    @Test
    @DisplayName("无违规 → PASS")
    void cleanPasses() {
        assertEquals(GateOutcome.PASS, gate.evaluate(CLEAN).outcome());
    }

    @Test
    @DisplayName("行为等价：与既有机械校验逐项一致（blocks↔hasHard、级别↔hasSoft、渲染逐字相同）")
    void equivalentToLegacyChecker() {
        for (String report : new String[]{null, "", HARD_SHELL, SOFT_ONLY, CLEAN}) {
            FinalReportFidelityChecker.Result legacy = FinalReportFidelityChecker.check(report);
            GateDecision decision = gate.evaluate(report);
            assertEquals(legacy.hasHard(), decision.blocks(), "blocks 应与 hasHard 等价: " + report);
            assertEquals(legacy.hasSoft(), decision.hasSeverity(GateSeverity.SOFT),
                    "SOFT 级别应与 hasSoft 等价: " + report);
            assertEquals(legacy.hardIssueList(), decision.detailList(GateSeverity.HARD),
                    "HARD 渲染应逐字一致: " + report);
            assertEquals(legacy.softIssueList(), decision.detailList(GateSeverity.SOFT),
                    "SOFT 渲染应逐字一致: " + report);
        }
    }
}
