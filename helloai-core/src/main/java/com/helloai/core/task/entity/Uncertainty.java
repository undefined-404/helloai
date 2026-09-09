package com.helloai.core.task.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 子任务不确定性申报（G-011 显式 JSONB 列 {@code sub_task.uncertainties} 的元素）。
 *
 * <p>kind 二值分级（命名消歧：不用 GAP，为未来 {@code sub_task.gap_kind} 的实现路径
 * 分类语义预留命名空间）：
 * <ul>
 *   <li>{@link #KIND_ASSUMPTION}：已申报假设——拆解时做出的推断，执行者可自行验证 /
 *       推翻；审查侧不因该假设的存在而驳回（按「假设是否被产出尊重」核验）；</li>
 *   <li>{@link #KIND_UNCONFIRMED}：待确认缺口——无法由现有信息证实，执行者须先验证
 *       再动手，验证不了走既有 BLOCKED 链上报；审查侧核验产出须含验证结论或上报痕迹。</li>
 * </ul>
 * 非法 kind 由拆解落库侧降级 UNCONFIRMED（fail-close，不丢弃——note 为自由文本，
 * 标注本身有信息量；见设计 D3）。空数组=无申报，执行/审查侧零注入。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Uncertainty {

    /** 已申报假设（拆解时做出的推断，执行者可自行验证 / 推翻）。 */
    public static final String KIND_ASSUMPTION = "ASSUMPTION";

    /** 待确认缺口（无法由现有信息证实，执行者须先验证再动手）。 */
    public static final String KIND_UNCONFIRMED = "UNCONFIRMED";

    /** 不确定性类别：ASSUMPTION / UNCONFIRMED（非法值由拆解落库侧降级 UNCONFIRMED）。 */
    private String kind;

    /** 不确定性描述（自由文本，标注本身有信息量，降级不丢弃）。 */
    private String note;
}