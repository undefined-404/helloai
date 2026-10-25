package com.helloai.core.task.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.helloai.common.constant.UncertaintyKind;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 子任务不确定性申报（G-011 显式 JSONB 列 {@code sub_task.uncertainties} 的元素）。
 *
 * <p>kind 四值分级（`TD-7` 由二值扩为四值；**值域唯一定义源 = {@link UncertaintyKind}**，
 * 本类的 {@code KIND_*} 字符串常量只是它的别名，供既有调用方与测试沿用）：
 * <ul>
 *   <li>{@link #KIND_ASSUMPTION}：已申报假设——拆解时做出的推断，执行者可自行验证 /
 *       推翻；审查侧不因该假设的存在而驳回（按「假设是否被产出尊重」核验）；</li>
 *   <li>{@link #KIND_UNCONFIRMED}：待确认缺口——无法由现有信息证实，也**不知去哪查 / 怎么算**，
 *       执行者须先确认再动手，确认不了走既有 BLOCKED 链上报；审查侧核验产出须含验证结论
 *       或上报痕迹；</li>
 *   <li>{@link #KIND_TO_LOOK_UP}：待检索事实——知道去哪儿查（联网 / 附件 / 知识库），
 *       执行者应先检索再动手（`TD-10` ledger 的 {@code facts-to-look-up}）；</li>
 *   <li>{@link #KIND_TO_DERIVE}：待推导事实——知道怎么算 / 怎么推，执行者应先推导再动手
 *       （{@code facts-to-derive}）。</li>
 * </ul>
 * 非法 kind 由落库侧降级 UNCONFIRMED（fail-close，不丢弃——note 为自由文本，
 * 标注本身有信息量；见设计 D3）。空数组=无申报，执行/审查侧零注入。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Uncertainty {

    /** 已申报假设（拆解时做出的推断，执行者可自行验证 / 推翻）。 */
    public static final String KIND_ASSUMPTION = UncertaintyKind.ASSUMPTION.name();

    /** 待确认缺口（不知去哪查 / 怎么算，执行者须先确认再动手）。 */
    public static final String KIND_UNCONFIRMED = UncertaintyKind.UNCONFIRMED.name();

    /** 待检索事实（知道去哪查：联网 / 附件 / 知识库；执行者应先检索再动手）。 */
    public static final String KIND_TO_LOOK_UP = UncertaintyKind.TO_LOOK_UP.name();

    /** 待推导事实（知道怎么算 / 怎么推；执行者应先推导再动手）。 */
    public static final String KIND_TO_DERIVE = UncertaintyKind.TO_DERIVE.name();

    /** 不确定性类别：ASSUMPTION / UNCONFIRMED / TO_LOOK_UP / TO_DERIVE（非法值由落库侧降级 UNCONFIRMED）。 */
    private String kind;

    /** 不确定性描述（自由文本，标注本身有信息量，降级不丢弃）。 */
    private String note;

    /**
     * 缺口引用标签（`TD-11`，形如 {@code U1} / {@code U2}；可空，存量行无此键）。
     *
     * <p>与 {@code task.port.UncertaintyDraft#ref()} 同名同形（该 record 是写入物料，
     * 本类是 JSONB 元素实体，字段名一变即静默改变拆解行为）。语义与约束见那边的说明：
     * <b>只当标签用、不当主键用</b>；{@code acceptancePreconditions} 用它指回缺口原文。
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证读取存量 JSONB 时不因缺键失败。</p>
     */
    private String ref;

    /**
     * 不写 {@code ref} 的便捷构造（`TD-11` 之前的调用方沿用；{@code @AllArgsConstructor}
     * 生成的三参构造与之并存，不冲突）。
     */
    public Uncertainty(String kind, String note) {
        this(kind, note, null);
    }
}
