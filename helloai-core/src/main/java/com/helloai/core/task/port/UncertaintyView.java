package com.helloai.core.task.port;

/**
 * 不确定项只读快照（task 域对外契约，RM5 批 4）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 review 域（依赖链下标 1）【高于】
 * 提供方 task 域（下标 2），故读契约落【提供方】{@code task.port}；
 * 消费方 {@code review → task.port} 为顺向合法，从此不再 import {@code task.entity.Uncertainty}。</p>
 *
 * <p><b>字段只纳入消费方实际读取项</b>：review 侧渲染 {@code [kind] note}；
 * 不携带 {@code Uncertainty.KIND_*} 常量依赖（消费方零常量 import）。</p>
 *
 * <p><b>`TD-11` 增列 {@code ref}</b>：核验侧要按 {@code context.acceptancePreconditions}
 * 里的标签（{@code U1}/{@code U2}）定位到具体缺口原文，才能把「这条验收卡在哪」写进驳回理由。
 * 与 {@code UncertaintyDraft#ref()} / {@code Uncertainty#ref} 同名同形。</p>
 */
public record UncertaintyView(
        String kind,
        String note,
        /** 缺口引用标签（`TD-11`，形如 {@code U1}；存量行可能为 {@code null}）。 */
        String ref
) {

    /** 兼容构造：`TD-11` 之前的读取方与测试沿用（无 ref）。 */
    public UncertaintyView(String kind, String note) {
        this(kind, note, null);
    }
}
