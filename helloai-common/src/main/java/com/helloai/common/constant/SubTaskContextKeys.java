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

    /**
     * 门禁结论（{@code Map}：{@code findings / ruleVersion / checkedAt | clearedAt}，``）。
     *
     * <p>写入：{@code SubTaskGateFindingsStore}（**唯一写点**，拆解 / 展开 / 重生成 / 编辑 /
     * 确认五条路径共用）。<b>结构恒存在</b>：无问题写 {@code findings: []} + {@code clearedAt}，
     * 消费方只看 {@code findings} 是否为空即可判「当前是否带病」，不必猜键有没有。</p>
     *
     * <p>旧实现挂 {@code task.context.gateFindings}：任务级单值 ⇒ 无法按草案过滤、{@code draftRef}
     * 只能是 LLM 输出序号、清理只能整体清。改挂草案后三者同时消失。</p>
     */
    public static final String GATE_FINDINGS = "gateFindings";

    /**
     * 模型/用户**显式声明**的 {@code acceptanceKind} 原文（{@code String}，合法枚举才写）。
     *
     * <p><b>为什么必须单独存</b>：{@code acceptance_kind} 列经 {@code AcceptanceKind.parse} 落库，
     * 缺失/非法值统一回落 {@code AUTOMATED}——落库后**无法区分「显式声明 AUTOMATED」与
     * 「没声明」**。而 kind 判据对这两种情况处置不同：显式声明与文本写法不符是 `block`，
     * 未声明只是 `warn`（存量产物常态）。丢了这个区分，普通文本会被判成「显式 AUTOMATED」
     * 而大面积误拦（`LOG-20261024-040` 已踩过一次）。</p>
     */
    public static final String ACCEPTANCE_KIND_DECLARED = "acceptanceKindDeclared";

    /**
     * 执行期重构轮次（{@code Integer}，每次进入 {@code RESTRUCTURING} 递增，`ADR-008` §12.3）。
     *
     * <p>与 {@code reworkCount} 正交：后者是「被驳回次数」的事实记录，改需求不等于驳回被撤销，
     * 故进入重构<b>不清零</b>；本键只回答「这是第几次重构」，供归因与界面展示。</p>
     */
    public static final String RESTRUCTURE_ROUND = "restructureRound";

    /** 进入重构的时刻（ISO-8601 字符串）。 */
    public static final String RESTRUCTURE_ENTERED_AT = "restructureEnteredAt";

    /** 人工填写的重构原因（{@code String}，可空）。 */
    public static final String RESTRUCTURE_REASON = "restructureReason";

    /**
     * 整改前的字段快照列表（{@code List<Map>}，元素含 title / content / deliverable / acceptance /
     * constraints / editedAt / editorId）。
     *
     * <p>只在「执行期编辑」时追加，首元素即**进入重构前的原始要求**——{@code G5} 用它作为
     * 「下游当初依赖的产出基线」。不落这份快照，「为什么这次跑出来和上次不一样」永远查不清
     * （`ADR-008` §12.3）。上限 {@code RESTRUCTURE_HISTORY_LIMIT} 条，防 JSONB 无限膨胀。</p>
     */
    public static final String RESTRUCTURE_HISTORY = "restructureHistory";

    /** 整改通过后回派发的时刻（ISO-8601 字符串）。 */
    public static final String RESTRUCTURE_APPLIED_AT = "restructureAppliedAt";

    /**
     * {@code G5_RUNTIME_CONTRACT_BREAK} 结论（结构同 {@link #GATE_FINDINGS}：findings / ruleVersion / checkedAt）。
     *
     * <p>本轮只记**通过**的结论；不通过时整体回滚（用户调整要求后重试），
     * 不留半截结论——与草案编辑「结论与编辑同生共死」同口径。</p>
     */
    public static final String RESTRUCTURE_GATE_FINDINGS = "restructureGateFindings";

    /** {@link #RESTRUCTURE_HISTORY} 的条数上限：超出时保留首条（原始基线）与最近条目。 */
    public static final int RESTRUCTURE_HISTORY_LIMIT = 50;

    private SubTaskContextKeys() {
    }
}
