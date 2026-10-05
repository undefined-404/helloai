package com.helloai.core.task.port;

/**
 * 不确定项写入物料（task 域对外契约，RM5 批 5a）。
 *
 * <p><b>字段名与 {@code task.entity.Uncertainty} 逐字对齐</b>（{@code kind} / {@code note}）——
 * 该结构同时是**拆解 Prompt 输出的 JSON 契约**（{@code prompts/planner-decompose.md}），
 * 字段名一变即静默改变拆解行为，故此处刻意保持同名同形。</p>
 */
public record UncertaintyDraft(
        String kind,
        String note
) {

    /** 已申报假设（值必须与 {@code task.entity.Uncertainty.KIND_ASSUMPTION} 逐字一致）。 */
    public static final String KIND_ASSUMPTION = "ASSUMPTION";

    /** 待确认缺口（值必须与 {@code task.entity.Uncertainty.KIND_UNCONFIRMED} 逐字一致）。 */
    public static final String KIND_UNCONFIRMED = "UNCONFIRMED";
}
