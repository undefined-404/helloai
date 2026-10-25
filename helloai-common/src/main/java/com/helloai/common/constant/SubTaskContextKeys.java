package com.helloai.common.constant;

/**
 * {@code sub_task.context} 的键常量（`TD-11` 起收口）。
 *
 * <p><b>为什么放 {@code helloai-common} 而不是某个域</b>：`sub_task.context` 是自由 JSONB，
 * 键名靠字符串约定。本轮的写入方在 **planner 域**（拆解落库时标注），读取方在 **review 域**
 * （核验时判是否豁免）。按依赖链 {@code planner(0) > review(1) > ...}，review 依赖 planner
 * 是**反向依赖**（会被 {@code check-arch-freeze} 阻断），planner 依赖 review 也不该有；
 * 故键名只能落在两侧都可依赖的最低层。与 {@link UncertaintyKind} 放同一处的理由完全一致。</p>
 *
 * <p>同族既有键（{@code reviewHistory} / {@code lastAutoReview} / {@code manualIntervention}）
 * 仍散落在写入方各自的私有常量里，暂不迁移——本轮只保证**新键**只有一个事实源。</p>
 */
public final class SubTaskContextKeys {

    /**
     * 验收前置未满足的引用清单（{@code List<String>}，元素形如 {@code U1}）。
     *
     * <p>语义 = 「本子任务的某几条验收要求，依赖某个**至今仍是待确认缺口**的外部事实，
     * 因此此刻无法被满足」。<b>只标注，不改任何既有字段</b>——`acceptance` 文本、
     * `acceptanceKind`、`dependsOn` 全部保持原样，也不拒绝拆解（`TD-11` 决策 13）。</p>
     *
     * <p>写入：{@code PlannerDecomposeAsyncServiceImpl#buildDrafts}（拆解落库前）。
     * 读取：核验侧（`TD-12`，尚未接线）。<b>键缺失 = 无标注</b>，与「标注了 0 条」等价，
     * 两侧都必须按「无豁免」处理（fail-safe）。</p>
     */
    public static final String ACCEPTANCE_PRECONDITIONS = "acceptancePreconditions";

    private SubTaskContextKeys() {
    }
}
