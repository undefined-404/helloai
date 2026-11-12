package com.helloai.core.planner.policy;

import com.helloai.common.util.DraftGateChecker;
import com.helloai.common.util.DraftGateChecker.DraftSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DraftGateChecker} 纯函数判定测试：只验证判定与 findings 形态，不涉及落库。
 *
 * <p><b>2026-11-08 口径</b>：入参是**已落库草案**的投影，{@code draftRef} 即 {@code sub_task.id}；
 * {@code G1_DEP_OUT_OF_RANGE} 的含义从「序号越界」改为「依赖指向不在本批次内的草案」。
 * 门 2 现在也承担 {@code acceptanceKind} 写法判定（旧实现在调用方抛异常，一条不匹配就连带整批作废）。</p>
 */
@DisplayName("DraftGateChecker")
class DraftGateCheckerTest {

    private static DraftSnapshot draft(Long id, String deliverable, String acceptance,
                                       String declaredKind, List<Long> dependsOn) {
        // 5 参便捷重载：code 缺省（存量无 code 行形态，ref() 回落 id 字符串）
        return draft(id, null, deliverable, acceptance, declaredKind, dependsOn);
    }

    private static DraftSnapshot draft(Long id, String code, String deliverable, String acceptance,
                                       String declaredKind, List<Long> dependsOn) {
        return new DraftSnapshot(id, code, "子任务", "内容描述", deliverable, acceptance, declaredKind, dependsOn);
    }

    private static List<String> ruleIds(DraftGateChecker.Result result) {
        return result.blocking().stream().map(DraftGateChecker.Finding::ruleId).toList();
    }

    @Test
    @DisplayName("合法批次：无阻断")
    void shouldPassCleanBatch() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(101L, "数据库建表脚本文件", "评审通过且无语法错误", null, null),
                draft(102L, "接口实现代码文件", "接口返回 200 状态码", null, List.of(101L))));

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.blocking()).isEmpty();
    }

    @Test
    @DisplayName("门 1：互相依赖成环 → G1_CYCLE 阻断，draftRef 为空（批次级问题）")
    void shouldBlockCycle() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(201L, "交付物甲完整清单", "验收判据甲说明文字内容", null, List.of(202L)),
                draft(202L, "交付物乙完整清单", "验收判据乙说明文字内容", null, List.of(201L))));

        assertThat(ruleIds(result)).containsExactly(DraftGateChecker.G1_CYCLE);
        assertThat(result.blocking().get(0).draftRef()).isNull();
    }

    @Test
    @DisplayName("门 1：依赖指向批次外草案 + 自引用分别命中，draftRef 指向真实草案 id")
    void shouldBlockUnknownAndSelfDependency() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(301L, "交付物甲完整清单", "验收判据甲说明文字内容", null, List.of(999L)),
                draft(302L, "交付物乙完整清单", "验收判据乙说明文字内容", null, List.of(302L))));

        assertThat(ruleIds(result)).containsExactly(
                DraftGateChecker.G1_DEP_OUT_OF_RANGE, DraftGateChecker.G1_SELF_DEP);
        // B2：draftRef 对外口径是稳定身份字符串（无 code 存量行回落 id 字符串）
        assertThat(result.blocking()).extracting(DraftGateChecker.Finding::draftRef)
                .containsExactly("301", "302");
    }

    @Test
    @DisplayName("门 1：重复依赖同一草案不算缺陷（不报悬空/自引用/环）")
    void shouldTolerateDuplicateDependency() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(401L, "交付物甲完整清单", "验收判据甲说明文字内容", null, null),
                draft(402L, "交付物乙完整清单", "验收判据乙说明文字内容", null, List.of(401L, 401L))));

        assertThat(result.hasBlocking()).isFalse();
    }

    @Test
    @DisplayName("门 2：字段空白 → G2_BLANK 阻断，subject 指出具体字段")
    void shouldBlockBlankField() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(501L, "交付物完整清单文本", "   ", null, null)));

        assertThat(result.blocking()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(DraftGateChecker.G2_BLANK);
            assertThat(f.severity()).isEqualTo("block");
            assertThat(f.subject()).isEqualTo("acceptance");
            assertThat(f.disposition()).isEqualTo("open");
            assertThat(f.ruleVersion()).isEqualTo(DraftGateChecker.RULE_VERSION);
            assertThat(f.draftRef()).isEqualTo("501");
        });
    }

    @Test
    @DisplayName("门 2：acceptance 低于 10 字符 → G2_LEN_MIN 阻断，证据含实际长度与阈值")
    void shouldBlockTooShortAcceptance() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(601L, "交付物完整清单文本", "通过即可", null, null)));

        assertThat(result.blocking()).extracting(DraftGateChecker.Finding::ruleId)
                .contains(DraftGateChecker.G2_LEN_MIN);
        assertThat(result.blocking().stream()
                .filter(f -> DraftGateChecker.G2_LEN_MIN.equals(f.ruleId())).findFirst().orElseThrow()
                .evidence()).contains("acceptance=4 字符 < 10");
    }

    @Test
    @DisplayName("门 2：deliverable 极短但不为空 → 不阻断（`三行状态回执` 存量合法形态）")
    void shouldNotBlockShortDeliverable() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(701L, "三行状态回执",
                        "三项可数断言：① 正文包含 API-CHECK-DONE ② 列出 3 行状态 ③ 无多余章节",
                        null, null),
                draft(702L, "三行纯文本",
                        "输出恰好 3 行，每行以 PASS/FAIL 开头并附 1 句理由说明", null, null)));

        assertThat(result.hasBlocking()).isFalse();
    }

    @Test
    @DisplayName("声明为 warn 的结论永不阻断")
    void warnSeverityNeverBlocks() {
        DraftGateChecker.Finding heuristic = new DraftGateChecker.Finding(
                DraftGateChecker.GATE_QUALITY, "G_HEURISTIC", DraftGateChecker.SEVERITY_WARN,
                "1", "deliverable", "启发式命中", DraftGateChecker.FIX_A,
                DraftGateChecker.DISPOSITION_OPEN, DraftGateChecker.RULE_VERSION);
        DraftGateChecker.Result result = new DraftGateChecker.Result(List.of(heuristic));

        assertThat(result.hasBlocking()).isFalse();
        assertThat(result.warnings()).singleElement()
                .satisfies(f -> assertThat(f.ruleId()).isEqualTo("G_HEURISTIC"));
    }

    @Test
    @DisplayName("门 2：显式声明 AUTOMATED 但文本无命令/断言 → G2_KIND_FORM_CONFLICT 阻断（该草案单条）")
    void shouldBlockExplicitKindConflict() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(801L, "需求说明文档", "内容完整、结构清晰，符合评审要求", "AUTOMATED", null)));

        assertThat(ruleIds(result)).contains(DraftGateChecker.G2_KIND_FORM_CONFLICT);
        assertThat(result.blocking().stream()
                .filter(f -> DraftGateChecker.G2_KIND_FORM_CONFLICT.equals(f.ruleId()))
                .findFirst().orElseThrow().draftRef()).isEqualTo("801");
    }

    @Test
    @DisplayName("门 2：未声明 kind 的普通描述 → 只 warn 不阻断（存量产物常态）")
    void shouldWarnWhenKindUndeclared() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(901L, "接口实现代码文件", "接口返回 200 状态码", null, null)));

        assertThat(result.hasBlocking()).isFalse();
    }

    @Test
    @DisplayName("门 2：未声明 kind 且为不可判定空话 → 仍阻断（空话在任何 kind 下都不可判定）")
    void shouldBlockVagueAcceptanceWhenKindUndeclared() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(1001L, "需求说明文档", "文档质量良好，体验良好", null, null)));

        assertThat(ruleIds(result)).contains(DraftGateChecker.G2_KIND_FORM_CONFLICT);
    }

    @Test
    @DisplayName("确认前复核：checkFields 只判单条质量，不判依赖结构")
    void checkFieldsShouldIgnoreDependencyStructure() {
        // 成环批次：整批门禁拦，单条复检不重复报环
        assertThat(DraftGateChecker.checkFields(List.of(
                draft(1101L, "交付物甲完整清单", "验收判据甲说明文字内容", null, List.of(1102L)),
                draft(1102L, "交付物乙完整清单", "验收判据乙说明文字内容", null, List.of(1101L))))
                .hasBlocking()).isFalse();

        // 字段被改坏：必须能拦住
        assertThat(DraftGateChecker.checkFields(List.of(
                draft(1103L, "交付物完整清单文本", "通过", null, null))).blocking())
                .extracting(DraftGateChecker.Finding::ruleId)
                .contains(DraftGateChecker.G2_LEN_MIN);
    }

    @Test
    @DisplayName("结论可按草案 id 分组；批次级问题键为 null（供落库侧绑到首条）")
    void shouldGroupFindingsByDraft() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(1201L, "交付物甲完整清单", "验收判据甲说明文字内容", null, List.of(1202L)),
                draft(1202L, "交付物乙完整清单", "验收判据乙说明文字内容", null, List.of(1201L))));

        var grouped = result.byDraft();
        assertThat(grouped).containsKey(null);
        assertThat(grouped.get(null)).extracting(DraftGateChecker.Finding::ruleId)
                .containsExactly(DraftGateChecker.G1_CYCLE);
    }

    @Test
    @DisplayName("B2：draftRef 暴露稳定 code 而非行 id（有 code 时 code 优先）")
    void shouldExposeStableCodeAsDraftRef() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(301L, "202611090301", "交付物甲完整清单", "验收判据甲说明文字内容", null, List.of(999L))));

        assertThat(result.blocking()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(DraftGateChecker.G1_DEP_OUT_OF_RANGE);
            // 位置身份 code，不是行 id
            assertThat(f.draftRef()).isEqualTo("202611090301");
        });
    }

    @Test
    @DisplayName("B2：存量无 code 草案回落 id 字符串（fail-closed，判定不失效）")
    void shouldFallbackToIdStringWhenCodeMissing() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(302L, "交付物乙完整清单", "验收判据乙说明文字内容", null, List.of(302L))));

        assertThat(result.blocking()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo(DraftGateChecker.G1_SELF_DEP);
            assertThat(f.draftRef()).isEqualTo("302");
        });
    }

    @Test
    @DisplayName("B2：byDraft 按 code 分组（无 code 回落 id 字符串）")
    void shouldGroupFindingsByCode() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(1201L, "202611091201", "交付物甲完整清单", "验收判据甲说明文字内容", null, List.of(999L)),
                draft(1202L, "", "接口实现代码文件", "接口返回 200 状态码", null, List.of(998L))));

        var grouped = result.byDraft();
        assertThat(grouped).containsKeys("202611091201", "1202");
        assertThat(grouped.get("202611091201")).extracting(DraftGateChecker.Finding::ruleId)
                .contains(DraftGateChecker.G1_DEP_OUT_OF_RANGE);
        assertThat(grouped.get("1202")).extracting(DraftGateChecker.Finding::ruleId)
                .contains(DraftGateChecker.G1_DEP_OUT_OF_RANGE);
    }

    // ── G3_DEPTH_LIMIT（`TD-16e-3`，s5-2）────────────────────────────

    @Test
    @DisplayName("G3：层级超过 archetype 上限 → 阻断，draftRef 为 code")
    void shouldBlockDepthOverLimit() {
        // DECIDE 档上限 1：`-001` 是第 1 层（合法），`-001-001` 是第 2 层（超限）
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(1301L, "202611091301", "交付物甲完整清单", "验收判据甲说明文字内容", null, null),
                draft(1302L, "202611091301-001", "交付物乙完整清单", "验收判据乙说明文字内容", null, null),
                draft(1303L, "202611091301-001-001", "交付物丙完整清单", "验收判据丙说明文字内容", null, null)
        ), 1);

        assertThat(ruleIds(result)).containsExactly(DraftGateChecker.G3_DEPTH_LIMIT);
        DraftGateChecker.Finding f = result.blocking().get(0);
        assertThat(f.draftRef()).isEqualTo("202611091301-001-001");
        assertThat(f.evidence()).contains("depth=2", "上限 1");
        assertThat(f.severity()).isEqualTo(DraftGateChecker.SEVERITY_BLOCK);
    }

    @Test
    @DisplayName("G3：层级恰好等于上限 → 放行（边界不是越界）")
    void shouldAllowDepthAtLimit() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(1311L, "202611091311", "交付物甲完整清单", "验收判据甲说明文字内容", null, null),
                draft(1312L, "202611091311-001", "交付物乙完整清单", "验收判据乙说明文字内容", null, null)
        ), 1);

        assertThat(result.hasBlocking()).isFalse();
    }

    @Test
    @DisplayName("G3：无 code 的存量行不判深度（fail-open，不能按根层兜底）")
    void shouldSkipDepthForLegacyRowsWithoutCode() {
        // maxDepth 取 0 才会把「根层」也判出来；若把无 code 当根层，这里就会误报
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(1321L, "交付物甲完整清单", "验收判据甲说明文字内容", null, null)
        ), 0);

        assertThat(result.hasBlocking()).isFalse();
    }

    @Test
    @DisplayName("G3：NO_DEPTH_LIMIT 时不判深度（调用方拿不到 archetype 的显式降级）")
    void shouldSkipDepthWhenLimitNotProvided() {
        DraftGateChecker.Result result = DraftGateChecker.check(List.of(
                draft(1331L, "202611091331-001-001-001", "交付物甲完整清单",
                        "验收判据甲说明文字内容", null, null)
        ), DraftGateChecker.NO_DEPTH_LIMIT);

        assertThat(result.hasBlocking()).isFalse();
    }

    @Test
    @DisplayName("G3：depthOf 由 code 推导层级，无 code 返回 -1（与 DraftCode.depth 同义）")
    void shouldDeriveDepthFromCode() {
        assertThat(DraftGateChecker.depthOf("202611091341")).isZero();
        assertThat(DraftGateChecker.depthOf("202611091341-001")).isEqualTo(1);
        assertThat(DraftGateChecker.depthOf("202611091341-001-001")).isEqualTo(2);
        assertThat(DraftGateChecker.depthOf("202611091341-001-001-001")).isEqualTo(3);
        assertThat(DraftGateChecker.depthOf(null)).isEqualTo(-1);
        assertThat(DraftGateChecker.depthOf("  ")).isEqualTo(-1);
    }
}
