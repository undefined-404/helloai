package com.helloai.core.task.port;

/**
 * Planner 选型端口（task 域只依赖下游 agent 域的值对象投影）。
 *
 * <p>按 §3.x 依赖方向红线：task 域不得 import planner 域类型；本端口定义在
 * task 域、由 planner 域 {@code PlannerAgentPicker} 实现（依赖倒置），
 * task 域（如最终报告生成）只依赖本接口。</p>
 *
 * <p><b>RM6 契约去实体（2026-10-04）</b>：返回类型由 {@code agent.entity.Agent}
 * 改为值对象 {@link PlannerAgentRef}（只读投影 id + name）——消费方 task 域不需要
 * agent 实体完整可变状态，返回实体会让两域实体耦合（CODE_STYLE §7.2 值对象纪律）。</p>
 */
public interface TaskPlannerPickerPort {

    /**
     * 按任务选 Planner（任务级 agent_policy.plannerAgentId 优先，其次澄清会话钉住，
     * 均失效时自动选择，pinned 失效宽松回退不抛错）。
     *
     * @param taskId 任务 id（可空，空则直接自动选择）
     * @return 选定的 Planner Agent 只读引用（id + name）
     */
    PlannerAgentRef pickForTask(Long taskId);
}
