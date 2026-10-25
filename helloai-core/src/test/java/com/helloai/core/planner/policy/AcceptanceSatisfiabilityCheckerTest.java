package com.helloai.core.planner.policy;

import com.helloai.core.task.port.UncertaintyDraft;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `TD-11` 验收契约可满足性标注：四条判据的正例与反例。
 *
 * <p>正例取 2026-10-25 事故契约**原文**（子任务 `2113947248843329537`，见
 * `LOG-20261025-014`），这样「判据能不能对上真实事故」是逐字可核的，而不是我编的样本。</p>
 */
class AcceptanceSatisfiabilityCheckerTest {

    /** 事故契约原文（`acceptance`），一字未改。 */
    private static final String INCIDENT_ACCEPTANCE = "STRUCTURAL：1) 证据条目总数 ≥15 条，A/B/C 各 ≥4 条；"
            + "2) 每家至少含 1 条定价页来源；3) 每条 5 个必填字段无空缺；"
            + "4) 抽查 3 条来源URL可打开且页面内容与原文摘录一致；5) 待确认项单独成表";

    private static final String INCIDENT_NOTE = "「竞品 A/B/C」是否为占位代号、对应哪三款真实产品，"
            + "需业务方拍板；未确认前无法锁定检索目标";

    private static UncertaintyDraft unconfirmed(String note, String ref) {
        return new UncertaintyDraft(UncertaintyDraft.KIND_UNCONFIRMED, note, ref);
    }

    @Test
    @DisplayName("验收①正例：事故契约原文 + UNCONFIRMED → 标注 U1（且带缺口原文）")
    void shouldAnnotateIncidentContract() {
        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                INCIDENT_ACCEPTANCE, List.of(unconfirmed(INCIDENT_NOTE, "U1")), List.of());

        assertThat(result.unmet()).isTrue();
        assertThat(result.preconditionRefs()).containsExactly("U1");
        assertThat(result.uncertaintyNotes()).containsExactly(INCIDENT_NOTE);
    }

    @Test
    @DisplayName("验收②反例：验收只含结构量词（不指向外部实体）→ 不标注")
    void shouldNotAnnotatePureStructuralQuantifiers() {
        String acceptance = "STRUCTURAL：输出 5 个章节，每章 ≥1 条结论，每条 ≤300 字";

        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                acceptance, List.of(unconfirmed(INCIDENT_NOTE, "U1")), List.of());

        assertThat(result.unmet()).isFalse();
        assertThat(result).isEqualTo(AcceptanceSatisfiabilityChecker.Result.NONE);
    }

    @Test
    @DisplayName("验收③反例：缺口是 TO_LOOK_UP（执行者可自助检索）→ 不标注")
    void shouldNotAnnotateLookUpOnly() {
        List<UncertaintyDraft> uncertainties = List.of(
                new UncertaintyDraft(UncertaintyDraft.KIND_TO_LOOK_UP, "竞品定价页需联网查", "U1"),
                new UncertaintyDraft(UncertaintyDraft.KIND_TO_DERIVE, "份额需按口径推算", "U2"));

        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                INCIDENT_ACCEPTANCE, uncertainties, List.of());

        assertThat(result.unmet()).isFalse();
    }

    @Test
    @DisplayName("反例：验收自己写了豁免措辞（模型已把逃生口写进验收）→ 不标注")
    void shouldNotAnnotateWhenAcceptanceCarriesExemption() {
        String acceptance = "STRUCTURAL：证据条目总数 ≥15 条且每条附来源 URL；"
                + "竞品代号映射未确认时不计 fail";

        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                acceptance, List.of(unconfirmed(INCIDENT_NOTE, "U1")), List.of());

        assertThat(result.unmet()).isFalse();
    }

    @Test
    @DisplayName("反例：dependsOn 指向上游澄清类草案（缺输入已由 DAG 表达）→ 不标注")
    void shouldNotAnnotateWhenUpstreamIsClarifyDraft() {
        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                INCIDENT_ACCEPTANCE, List.of(unconfirmed(INCIDENT_NOTE, "U1")),
                List.of("澄清竞品代号映射 向业务方确认 A/B/C 对应哪三款真实产品"));

        assertThat(result.unmet()).isFalse();
    }

    @Test
    @DisplayName("混合缺口：只取 UNCONFIRMED，保留模型给的 ref；ASSUMPTION 不参与")
    void shouldOnlyPickUnconfirmedAndKeepProvidedRef() {
        List<UncertaintyDraft> uncertainties = List.of(
                new UncertaintyDraft(UncertaintyDraft.KIND_ASSUMPTION, "假设竞品均为 SaaS", "C1"),
                unconfirmed("A/B/C 未确认", "C2"));

        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                INCIDENT_ACCEPTANCE, uncertainties, List.of());

        assertThat(result.preconditionRefs()).containsExactly("C2");
        assertThat(result.uncertaintyNotes()).containsExactly("A/B/C 未确认");
    }

    @Test
    @DisplayName("ref 缺失时兜底 U<序号>，且与模型已占用的 ref 不冲突（保证引用可唯一定位缺口）")
    void shouldFallbackToPositionalRefWithoutCollision() {
        // 第 1 条没给 ref → 补 U1；第 2 条模型给了 U1，已被占用 → 顺延为 U2
        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                INCIDENT_ACCEPTANCE,
                List.of(new UncertaintyDraft(UncertaintyDraft.KIND_UNCONFIRMED, "缺口一"),
                        unconfirmed("缺口二", "U1")),
                List.of());

        assertThat(result.preconditionRefs()).containsExactly("U1", "U2");
        assertThat(result.uncertaintyNotes()).containsExactly("缺口一", "缺口二");
    }

    @Test
    @DisplayName("ensureRefs：保留模型给的语义化 ref（如 C1），不强行改成 U<n>")
    void shouldKeepModelProvidedRef() {
        List<UncertaintyDraft> refs = AcceptanceSatisfiabilityChecker.ensureRefs(List.of(
                unconfirmed("缺口一", "C1"),
                new UncertaintyDraft(UncertaintyDraft.KIND_UNCONFIRMED, "缺口二")));

        assertThat(refs).extracting(UncertaintyDraft::ref).containsExactly("C1", "U2");
    }

    @Test
    @DisplayName("空/无缺口：acceptance 命中来源信号但 uncertainties 为空 → 不标注")
    void shouldNotAnnotateWithoutUncertainties() {
        assertThat(AcceptanceSatisfiabilityChecker.check(INCIDENT_ACCEPTANCE, List.of(), List.of()).unmet())
                .isFalse();
        assertThat(AcceptanceSatisfiabilityChecker.check(INCIDENT_ACCEPTANCE, null, null).unmet())
                .isFalse();
    }

    @Test
    @DisplayName("任务级判据：本条无 UNCONFIRMED 但任务级有 → 标注（2026-10-25 准确率修正）")
    void shouldAnnotateWhenTaskLevelHasUnconfirmed() {
        // 模拟 LOG-20261025-016 场景：草案 2（竞品 A 事实采集）自己报 TO_LOOK_UP，
        // 但任务级有 UNCONFIRMED（草案 7/8 申报的「A/B/C 未确认」）
        List<UncertaintyDraft> itemUncertainties = List.of(
                new UncertaintyDraft(UncertaintyDraft.KIND_TO_LOOK_UP, "竞品 A 定价页需联网查", "U1"));
        List<UncertaintyDraft> taskLevelUncertainties = List.of(
                new UncertaintyDraft(UncertaintyDraft.KIND_TO_LOOK_UP, "竞品 A 定价页需联网查", "U1"),
                unconfirmed(INCIDENT_NOTE, "U2"));

        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                INCIDENT_ACCEPTANCE, itemUncertainties, List.of(), taskLevelUncertainties);

        assertThat(result.unmet()).isTrue();
        assertThat(result.preconditionRefs()).containsExactly("U2");
        assertThat(result.uncertaintyNotes()).containsExactly(INCIDENT_NOTE);
    }

    @Test
    @DisplayName("任务级判据：任务级无 UNCONFIRMED → 不标注（退化为单条口径）")
    void shouldNotAnnotateWhenTaskLevelHasNoUnconfirmed() {
        List<UncertaintyDraft> itemUncertainties = List.of(
                new UncertaintyDraft(UncertaintyDraft.KIND_TO_LOOK_UP, "竞品 A 定价页需联网查", "U1"));
        List<UncertaintyDraft> taskLevelUncertainties = List.of(
                new UncertaintyDraft(UncertaintyDraft.KIND_TO_LOOK_UP, "竞品 A 定价页需联网查", "U1"),
                new UncertaintyDraft(UncertaintyDraft.KIND_TO_DERIVE, "份额需按口径推算", "U2"));

        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                INCIDENT_ACCEPTANCE, itemUncertainties, List.of(), taskLevelUncertainties);

        assertThat(result.unmet()).isFalse();
    }

    @Test
    @DisplayName("任务级判据：taskLevelUncertainties 为 null → 退化为单条口径")
    void shouldFallbackToItemLevelWhenTaskLevelIsNull() {
        AcceptanceSatisfiabilityChecker.Result result = AcceptanceSatisfiabilityChecker.check(
                INCIDENT_ACCEPTANCE, List.of(unconfirmed(INCIDENT_NOTE, "U1")), List.of(), null);

        assertThat(result.unmet()).isTrue();
        assertThat(result.preconditionRefs()).containsExactly("U1");
    }
}
