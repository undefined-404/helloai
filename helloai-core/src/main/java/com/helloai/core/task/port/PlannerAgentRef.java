package com.helloai.core.task.port;

/**
 * Planner Agent 只读引用（RM6 端口契约去实体，2026-10-04）。
 *
 * <p><b>为什么是值对象而非实体</b>（CODE_STYLE §7.2 值对象纪律）：{@link TaskPlannerPickerPort}
 * 的消费方（task 域）只需要「选中的 Planner 是谁」的只读投影（id + name），不需要 agent 域实体
 * 的完整可变状态。返回实体（{@code agent.entity.Agent}）会让 task 域与 agent 域实体耦合，
 * 实体字段一变、task 域即受牵连；改返回本 record 后，跨域契约只暴露必要投影。</p>
 *
 * <p><b>归属</b>：本值对象随 {@link TaskPlannerPickerPort} 放在 <b>task 域</b>（消费方定义）。
 * 依赖链 {@code planner > review > task}，消费方 task 低于提供方 planner ⇒ 端口与值对象均放消费方，
 * 实现侧 {@code PlannerAgentPicker} 依赖 {@code task.port} 属顺向合法（§7.2 情形①）。</p>
 *
 * @param id   Planner Agent 主键（不可空）
 * @param name Planner Agent 名称（用于日志 / 事件关联）
 */
public record PlannerAgentRef(Long id, String name) {
}
