package com.helloai.core.task.port;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helloai.common.constant.UncertaintyKind;

import java.util.List;

/**
 * 不确定项写入物料（task 域对外契约，RM5 批 5a）。
 *
 * <p><b>本 record 同时是拆解 Prompt 输出的 JSON 契约的元素形状</b>，故显式声明
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}：模型多写一个键（如 {@code severity}）
 * 不该让整条不确定性解析失败——`resolveUncertainties` 的兜底是「整批回落空列表」，
 * 那会让一条多余字段把**整个草案的缺口申报全部丢掉**，而调用方看不出异常。</p>
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
 * @param blockedBy 该缺口**卡住本批哪几条草案**（`TD-13 S2`，本批 1-based 草案序号数组；
 *             可空 = 未申报）。<b>这是 `TD-11` 断点① 的补齐</b>：`ref` 被钉成本子任务局部，
 *             模型没有字段能表达「这个缺口影响草案 2/3/4」，只能就近贴在草案 7/8 上——
 *             里真正被卡的 3 条草案一条标注都没拿到，根因就在这里。
 *             归一化（越界 / 自引用 / 去重）由落库侧统一做并留 WARN；服务端不据此改
 *             {@code dependsOn}（影响面 ≠ 依赖边，两者语义不同，不得互换）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UncertaintyDraft(
        String kind,
        String note,
        String ref,
        List<Integer> blockedBy
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
        this(kind, note, null, null);
    }

    /**
     * 不写 {@code blockedBy} 的便捷构造（`TD-13 S2` 之前的调用方与测试沿用）。
     *
     * <p>与上面的两参构造同理：{@code blockedBy} 缺失即「未申报影响面」，不是空数组——
     * 空数组的语义是「模型明确声明不影响任何草案」，消费侧对两者的展示措辞不同。</p>
     */
    public UncertaintyDraft(String kind, String note, String ref) {
        this(kind, note, ref, null);
    }
}


