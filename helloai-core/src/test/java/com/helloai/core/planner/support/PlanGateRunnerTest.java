package com.helloai.core.planner.support;

import com.helloai.common.config.PlannerDecomposeProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskArchetype;
import com.helloai.common.util.DraftGateChecker;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.support.SubTaskGateFindingsStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * {@link PlanGateRunner} 的 findings 落库转换（{@code B2}，2026-11-09）。
 *
 * <p><b>为什么单独测这一层</b>：{@code DraftGateChecker.byDraft()} 的键已从草案 {@code id} 换成
 * 稳定 {@code code}（findings 内容以 code 暴露），而 {@link SubTaskGateFindingsStore} 仍按
 * {@code sub_task.id} 落库——`PlanGateRunner` 是这两套身份之间的唯一翻译点。翻译错位（漏翻、按
 * code 查 id、null 键丢失）不会报错、只会让结论静默挂错行，必须锁死。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanGateRunner（B2 翻译链路）")
class PlanGateRunnerTest {

    private static final long TASK_ID = 100L;

    @Mock
    private SubTaskGateFindingsStore subTaskGateFindingsStore;
    @Mock
    private TaskTimelineService taskTimelineService;

    private PlanGateRunner runner() {
        return new PlanGateRunner(subTaskGateFindingsStore, taskTimelineService,
                new PlannerDecomposeProperties());
    }

    /** 27 参 canonical 构造（末位 code），与拆解测试同型。 */
    private SubTaskView draftWithCode(long id, String title, String code) {
        return new SubTaskView(id, TASK_ID, SubTaskStatus.PENDING, null, null, title, "内容描述", "交付物", "接口返回 200 状态码", null, null, null, List.of(), List.of(), null, "MEDIUM", null, List.of(), null, null, null, null, null, null, null, code);
    }

    private DraftGateChecker.Finding finding(String ref, String ruleId) {
        return new DraftGateChecker.Finding(DraftGateChecker.GATE_STRUCTURE, ruleId,
                DraftGateChecker.SEVERITY_BLOCK, ref, "dependsOn", "证据", DraftGateChecker.FIX_A,
                DraftGateChecker.DISPOSITION_OPEN, DraftGateChecker.RULE_VERSION);
    }

    @Test
    @DisplayName("persist：code 分组键翻译回 sub_task.id，批次级 null 键保留、挂首条")
    void persistTranslatesCodeKeysBackToSubTaskIds() {
        PlanGateRunner runner = runner();
        List<SubTaskView> drafts = List.of(
                draftWithCode(81L, "草案一", "202611090001"),
                draftWithCode(82L, "草案二", "202611090002"));
        DraftGateChecker.Result result = new DraftGateChecker.Result(List.of(
                finding("202611090001", "G1_DEP_OUT_OF_RANGE"),
                finding(null, "G1_CYCLE")));

        runner.persist(drafts, result);

        Map<Long, List<DraftGateChecker.Finding>> expected = new HashMap<>();
        expected.put(81L, List.of(finding("202611090001", "G1_DEP_OUT_OF_RANGE")));
        expected.put(null, List.of(finding(null, "G1_CYCLE")));
        verify(subTaskGateFindingsStore).applyBatch(List.of(81L, 82L), expected, 81L);
    }

    @Test
    @DisplayName("persistInTx：与 persist 同一翻译（同事务分支）")
    void persistInTxSharesSameTranslation() {
        PlanGateRunner runner = runner();
        List<SubTaskView> drafts = List.of(
                draftWithCode(81L, "草案一", "202611090001"),
                draftWithCode(82L, "草案二", "202611090002"));
        DraftGateChecker.Result result = new DraftGateChecker.Result(List.of(
                finding("202611090001", "G1_DEP_OUT_OF_RANGE")));

        runner.persistInTx(drafts, result);

        verify(subTaskGateFindingsStore).applyBatchInTx(
                List.of(81L, 82L),
                Map.of(81L, List.of(finding("202611090001", "G1_DEP_OUT_OF_RANGE"))),
                81L);
    }

    @Test
    @DisplayName("persist：存量无 code 草案回落 id 字符串，仍能翻译回 id（fail-closed）")
    void persistFallsBackToIdStringWhenCodeMissing() {
        PlanGateRunner runner = runner();
        List<SubTaskView> drafts = List.of(
                draftWithCode(301L, "存量草案", null));
        DraftGateChecker.Result result = new DraftGateChecker.Result(List.of(
                finding("301", "G2_LEN_MIN")));

        runner.persist(drafts, result);

        verify(subTaskGateFindingsStore).applyBatch(
                List.of(301L),
                Map.of(301L, List.of(finding("301", "G2_LEN_MIN"))),
                301L);
    }

    @Test
    @DisplayName("refOf：code 优先；blank/null code 回落 id 字符串")
    void refOfPrefersCode() {
        assertThat(PlanGateRunner.refOf(draftWithCode(1L, "有 code", "202611090001")))
                .isEqualTo("202611090001");
        assertThat(PlanGateRunner.refOf(draftWithCode(2L, "blank code", "  ")))
                .isEqualTo("2");
        assertThat(PlanGateRunner.refOf(draftWithCode(3L, "null code", null)))
                .isEqualTo("3");
    }

    @Test
    @DisplayName("evaluate：上限按 archetype 分档——同一份 depth=2 草案，DECIDE 拦、BUILD 放")
    void evaluateAppliesArchetypeDepthLimit() {
        PlanGateRunner runner = runner();
        // `202611090001-001-001` = depth 2：`DECIDE` 上限 1 应拦，`BUILD` 上限 3 应放
        List<SubTaskView> drafts = List.of(
                draftWithCode(91L, "深层草案", "202611090001-001-001"));

        assertThat(runner.evaluate(drafts, TaskArchetype.DECIDE).blocking())
                .extracting(DraftGateChecker.Finding::ruleId)
                .containsExactly(DraftGateChecker.G3_DEPTH_LIMIT);
        assertThat(runner.evaluate(drafts, TaskArchetype.BUILD).hasBlocking()).isFalse();
    }

    @Test
    @DisplayName("evaluate：archetype 为 null 按 BUILD 口径（不是 NO_DEPTH_LIMIT）")
    void evaluateTreatsNullArchetypeAsBuild() {
        // 合法 code 最多 4 段（depth 3）恰好等于 BUILD 上限，用真实档位证不出差异；
        // 把 BUILD 临时收到 1，depth 2 在 null 下必须被拦——才能证明走的是 BUILD 而不是「不判」。
        PlannerDecomposeProperties props = new PlannerDecomposeProperties();
        props.getMaxExpandDepth().setBuild(1);
        PlanGateRunner runner = new PlanGateRunner(subTaskGateFindingsStore, taskTimelineService, props);
        List<SubTaskView> drafts = List.of(
                draftWithCode(92L, "深层草案", "202611090001-001-001"));

        assertThat(runner.evaluate(drafts, null).blocking())
                .extracting(DraftGateChecker.Finding::ruleId)
                .containsExactly(DraftGateChecker.G3_DEPTH_LIMIT);
    }
}