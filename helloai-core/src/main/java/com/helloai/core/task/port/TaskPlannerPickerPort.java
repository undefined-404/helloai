package com.helloai.core.task.port;

import com.helloai.core.agent.entity.Agent;

/**
 * Planner 选型端口（task 域只依赖下游 agent 域实体）。
 *
 * <p>按 §3.x 依赖方向红线：task 域不得 import planner 域类型；本端口定义在
 * task 域、由 planner 域 {@code PlannerAgentPicker} 实现（依赖倒置），
 * task 域（如最终报告生成）只依赖本接口。</p>
 */
public interface TaskPlannerPickerPort {

    /**
     * 按任务选 Planner（任务级 agent_policy.plannerAgentId 优先，其次澄清会话钉住，
     * 均失效时自动选择，pinned 失效宽松回退不抛错）。
     *
     * @param taskId 任务 id（可空，空则直接自动选择）
     * @return 选定的 Planner Agent
     */
    Agent pickForTask(Long taskId);
}