package com.helloai.core.agent.quality.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GateDecision} 单测（RM12 / B4 Quality Gate 泛化契约）。
 *
 * <p>重点锁定 {@link GateDecision#detailList(GateSeverity)} 的渲染格式——
 * 它必须与既有机械校验 {@code FinalReportFidelityChecker.Result#issueList} 逐字一致，
 * 否则接线后对外事件 / 日志内容会漂移。</p>
 */
class GateDecisionTest {

    @Test
    @DisplayName("pass()/skip()：空发现项；skip 不得被视为通过")
    void factoryMethods() {
        assertEquals(GateOutcome.PASS, GateDecision.pass().outcome());
        assertTrue(GateDecision.pass().findings().isEmpty());
        assertEquals(GateOutcome.SKIP, GateDecision.skip().outcome());
        assertFalse(GateDecision.skip().blocks());
    }

    @Test
    @DisplayName("blocks()/hasSeverity()：按结论与级别判定")
    void predicates() {
        GateDecision block = new GateDecision(GateOutcome.BLOCK,
                List.of(new GateFinding(GateSeverity.HARD, "shell_reference", "命中")));
        assertTrue(block.blocks());
        assertTrue(block.hasSeverity(GateSeverity.HARD));
        assertFalse(block.hasSeverity(GateSeverity.SOFT));
    }

    @Test
    @DisplayName("detailList：序号. [规则] 描述 逐行，末尾 trim（与旧 issueList 逐字一致）")
    void detailListFormat() {
        GateDecision d = new GateDecision(GateOutcome.BLOCK, List.of(
                new GateFinding(GateSeverity.HARD, "shell_reference", "命中A"),
                new GateFinding(GateSeverity.SOFT, "coverage_table_missing", "软A"),
                new GateFinding(GateSeverity.HARD, "code_fence_unbalanced", "命中B")));
        // 各级别独立编号（从 1 起），且顺序保持原发现项顺序
        assertEquals("1. [shell_reference] 命中A\n2. [code_fence_unbalanced] 命中B",
                d.detailList(GateSeverity.HARD));
        assertEquals("1. [coverage_table_missing] 软A", d.detailList(GateSeverity.SOFT));
    }

    @Test
    @DisplayName("发现项为 null 时防御式收敛为空清单（不抛异常）")
    void nullFindings() {
        assertTrue(new GateDecision(GateOutcome.PASS, null).findings().isEmpty());
    }
}
