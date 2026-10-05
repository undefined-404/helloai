package com.helloai.core.task.port;

/**
 * 不确定项只读快照（task 域对外契约，RM5 批 4）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 review 域（依赖链下标 1）【高于】
 * 提供方 task 域（下标 2），故读契约落【提供方】{@code task.port}；
 * 消费方 {@code review → task.port} 为顺向合法，从此不再 import {@code task.entity.Uncertainty}。</p>
 *
 * <p><b>字段只纳入消费方实际读取项</b>：review 侧仅渲染 {@code [kind] note}，
 * 故只暴露两列，不携带 {@code Uncertainty.KIND_*} 常量依赖（消费方零常量 import）。</p>
 */
public record UncertaintyView(
        String kind,
        String note
) {
}
