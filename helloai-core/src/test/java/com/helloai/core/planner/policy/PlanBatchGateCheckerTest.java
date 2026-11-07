package com.helloai.core.planner.policy;

import com.helloai.core.planner.service.PlannerAnalysisService.PlanDraftItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `TD-13 S4` 门 1/2 纯函数检查器测试：只验证判定与 findings 形态，不涉及落库。
 *
 * <p>`s4-3` 起门 2 只剩阻断项（字段空白 / 字符下限）；`G2_NOUN_MISMATCH` 因中文
 * 「同一个东西」缺乏可靠判据、实测 21% 误报，已降为 debug 日志，本测试锁定
 * 「它不再产出任何 finding」。</p>
 */
@DisplayName("PlanBatchGateChecker")
class PlanBatchGateCheckerTest {

    private static PlanDraftItem item(String deliverable, String acceptance, List<Integer> dependsOn) {
        PlanDraftItem item = new PlanDraftItem();
        item.setTitle("子任务");
        item.setContent("内容描述");
        item.setDeliverable(deliverable);
        item.setAcceptance(acceptance);
        item.setDependsOn(dependsOn);
        return item;
    }

    private static List<String> ruleIds(PlanBatchGateChecker.Result result) {
        return result.blocking().stream().map(PlanBatchGateChecker.Finding::ruleId).toList();
    }

    @Test
    @DisplayName("合法批次：无阻断")
    void shouldPassCleanBatch() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("数据库建表脚本文件", "评审通过且无语法错误", null),
                item("接口实现代码文件", "接口返回 200 状态码", List.of(1))));

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.blocking()).isEmpty();
    }

    @Test
    @DisplayName("门 1：互相依赖成环 → G1_CYCLE 阻断，draftRef 为空（批次级问题）")
    void shouldBlockCycle() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("交付物甲完整清单", "验收判据甲说明文字内容", List.of(2)),
                item("交付物乙完整清单", "验收判据乙说明文字内容", List.of(1))));

        assertThat(ruleIds(result)).containsExactly(PlanBatchGateChecker.G1_CYCLE);
        assertThat(result.blocking().get(0).draftRef()).isNull();
    }

    @Test
    @DisplayName("门 1：越界序号与自引用分别命中，draftRef 指向真实草案序")
    void shouldBlockOutOfRangeAndSelfDependency() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("交付物甲完整清单", "验收判据甲说明文字内容", List.of(3)),
                item("交付物乙完整清单", "验收判据乙说明文字内容", List.of(2))));

        assertThat(ruleIds(result)).containsExactly(
                PlanBatchGateChecker.G1_DEP_OUT_OF_RANGE, PlanBatchGateChecker.G1_SELF_DEP);
        assertThat(result.blocking()).extracting(PlanBatchGateChecker.Finding::draftRef)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("门 1：重复依赖同一序号不算缺陷（不报越界/自引用/环）")
    void shouldTolerateDuplicateDependency() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("交付物甲完整清单", "验收判据甲说明文字内容", null),
                item("交付物乙完整清单", "验收判据乙说明文字内容", List.of(1, 1))));

        assertThat(result.hasBlocking()).isFalse();
    }

    @Test
    @DisplayName("门 2：字段空白 → G2_BLANK 阻断，subject 指出具体字段")
    void shouldBlockBlankField() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("交付物完整清单文本", "   ", null)));

        assertThat(result.blocking()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(PlanBatchGateChecker.G2_BLANK);
            assertThat(f.severity()).isEqualTo(PlanBatchGateChecker.SEVERITY_BLOCK);
            // schema 口径是小写（block/warn/info），不是大写枚举
            assertThat(f.severity()).isEqualTo("block");
            assertThat(f.subject()).isEqualTo("acceptance");
            assertThat(f.disposition()).isEqualTo("open");
            assertThat(f.ruleVersion()).isEqualTo(PlanBatchGateChecker.RULE_VERSION);
        });
    }

    @Test
    @DisplayName("门 2：acceptance 低于 10 字符 → G2_LEN_MIN 阻断，证据含实际长度与阈值")
    void shouldBlockTooShortAcceptance() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("交付物完整清单文本", "通过即可", null)));

        assertThat(result.blocking()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(PlanBatchGateChecker.G2_LEN_MIN);
            assertThat(f.evidence()).contains("acceptance=4 字符 < 10");
        });
    }

    @Test
    @DisplayName("门 2：deliverable 极短但不为空 → 不阻断（`三行状态回执` 存量合法形态，s4-4）")
    void shouldNotBlockShortDeliverable() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("三行状态回执", "三项可数断言：① 正文包含 API-CHECK-DONE ② 列出 3 行状态 ③ 无多余章节", null),
                item("三行纯文本", "输出恰好 3 行，每行以 PASS/FAIL 开头并附 1 句理由说明", null)));

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.blocking()).isEmpty();
    }

    @Test
    @DisplayName("声明为 warn 的规则永不阻断（block 权只给零歧义确定性规则）")
    void warnSeverityNeverBlocks() {
        PlanBatchGateChecker.Finding heuristic = new PlanBatchGateChecker.Finding(
                PlanBatchGateChecker.GATE_QUALITY, "G_HEURISTIC", PlanBatchGateChecker.SEVERITY_WARN,
                1, "deliverable", "启发式命中", PlanBatchGateChecker.FIX_A,
                PlanBatchGateChecker.DISPOSITION_OPEN, PlanBatchGateChecker.RULE_VERSION);
        PlanBatchGateChecker.Result result = new PlanBatchGateChecker.Result(List.of(heuristic));

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.blocking()).isEmpty();
        assertThat(result.warnings()).singleElement()
                .satisfies(f -> assertThat(f.ruleId()).isEqualTo("G_HEURISTIC"));
    }

    @Test
    @DisplayName("门 2：交付物专名未复述于验收 → 不产出 finding（s4-3 降级；实测 21% 误报）")
    void shouldNotEmitFindingWhenQuotedTermMissingFromAcceptance() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("《验收条件冲突说明与人工裁定请求》", "文档须满足 3 项可数结构断言", null),
                item("输出《巡检台账》契约文档", "输出 1 份需求说明文档，含 5 个章节", null)));

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.blocking()).isEmpty();
    }

    @Test
    @DisplayName("门 2：嵌套引号交付物不产出 finding（曾抽成带前导「的残串）")
    void shouldNotEmitFindingOnNestedQuoteDeliverable() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("《「外部失败回执」口径依据卡》", "卡片须包含 4 个字段与 2 条裁决规则", null)));

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.blocking()).isEmpty();
    }

    @Test
    @DisplayName("门 2：英文路径/文件名不作专名 → 不产出 finding（真机 2/2 误报的回归用例）")
    void shouldNotEmitFindingOnAsciiPathTerms() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("契约文档 docs/contract-inspection-ledger.md",
                        "交付的契约文档须满足以下可数结构断言：数据模型章节列出 6 个台账字段", null)));

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.blocking()).isEmpty();
    }

    @Test
    @DisplayName("阻断消息可读：含草案序号、证据原文与 ruleId")
    void blockMessageShouldCarryDraftSeqEvidenceAndRuleId() {
        PlanBatchGateChecker.Result result = PlanBatchGateChecker.check(List.of(
                item("交付物完整清单文本", "通过即可", null)));

        assertThat(result.blockMessage())
                .startsWith("拆解结果未通过门 1/2：第 1 条")
                .contains("G2_LEN_MIN");
    }
}
