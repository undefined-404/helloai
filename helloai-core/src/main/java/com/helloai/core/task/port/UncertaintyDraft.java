package com.helloai.core.task.port;

import com.helloai.common.constant.UncertaintyKind;

/**
 * 不确定项写入物料（task 域对外契约，RM5 批 5a）。
 *
 * <p><b>字段名与 {@code task.entity.Uncertainty} 逐字对齐</b>（{@code kind} / {@code note} /
 * {@code ref}）——该结构同时是**拆解 Prompt 输出的 JSON 契约**
 * （{@code prompts/planner-decompose.md}），字段名一变即静默改变拆解行为，故此处刻意保持同名同形。</p>
 *
 * <p><b>kind 值域的单一定义源 = {@link UncertaintyKind}</b>（`TD-7` 收口）：此前本类与
 * {@code task.entity.Uncertainty} 各写一份字面量、靠注释「必须逐字一致」约束，属
 * 「同一事实双写必分叉」。现两处均由此枚举派生——planner 域不依赖 {@code task.entity}
 * （反向依赖被架构守卫阻断），故值域只能落在 {@code helloai-common}。</p>
 *
 * @param kind 不确定性类别（`TD-7` 四值，值域见 {@link UncertaintyKind}）
 * @param note 不确定性描述（自由文本）
 * @param ref  缺口引用标签（`TD-11`，形如 {@code U1} / {@code U2}；可空）。
 *             <b>只当标签用，不当主键用</b>：{@code acceptancePreconditions} 记的是
 *             「这条验收卡在哪个缺口上」，人眼要对得上本数组的哪一项。选它而非数组下标，
 *             是因为重拆 / {@code regenerateById} 会改顺序而下标静默错位；选它而非 note 哈希，
 *             是因为排障时哈希对不上人眼。**服务端不做重排**；解析不到即「不豁免」
 *             （fail-safe 保持现状严格），见计划 §6 风险 8。
 *             模型可自行输出；缺失时由落库侧 {@code normalizeUncertainties} 统一补
 *             {@code U<序号>}（生成规则只有一个事实源）。
 */
public record UncertaintyDraft(
        String kind,
        String note,
        String ref
) {

    /** 已申报假设（= {@link UncertaintyKind#ASSUMPTION}）。 */
    public static final String KIND_ASSUMPTION = UncertaintyKind.ASSUMPTION.name();

    /** 待确认缺口（= {@link UncertaintyKind#UNCONFIRMED}）。 */
    public static final String KIND_UNCONFIRMED = UncertaintyKind.UNCONFIRMED.name();

    /** 待检索事实（= {@link UncertaintyKind#TO_LOOK_UP}，`TD-7`）。 */
    public static final String KIND_TO_LOOK_UP = UncertaintyKind.TO_LOOK_UP.name();

    /** 待推导事实（= {@link UncertaintyKind#TO_DERIVE}，`TD-7`）。 */
    public static final String KIND_TO_DERIVE = UncertaintyKind.TO_DERIVE.name();

    /**
     * 不写 {@code ref} 的便捷构造（`TD-11` 之前的调用方与测试沿用）。
     *
     * <p>{@code ref} 的语义与约束见 {@link #ref()}；本构造只把「没有引用」显式写成
     * {@code null}，由落库侧（{@code normalizeUncertainties}）统一补齐，**不在构造器里
     * 生成**——生成规则只有一个事实源，否则两处都会各自编号。</p>
     */
    public UncertaintyDraft(String kind, String note) {
        this(kind, note, null);
    }
}


