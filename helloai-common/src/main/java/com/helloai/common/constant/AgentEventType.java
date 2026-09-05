package com.helloai.common.constant;

/**
 * Agent 事件类型（Phase 0 B1，ADR-001 Run/Turn/Step 模型配套）。
 *
 * <p>事件分类（写 {@code agent_event.event_type}，snake_case）：
 * <ul>
 *   <li><b>Run 级</b>：{@link #RUN_CREATED} / {@link #RUN_COMPLETED}（一次需求完整执行）</li>
 *   <li><b>Task 级</b>：{@link #TASK_CREATED} / {@link #TASK_ASSIGNED}</li>
 *   <li><b>Turn 级</b>：{@link #AGENT_STARTED} / {@link #SKILL_RESOLVED} /
 *       {@link #CONTEXT_BUILT} / {@link #AGENT_COMPLETED}（一次 Agent 工作周期）</li>
 *   <li><b>Step 级</b>：{@link #TOOL_CALL_STARTED} / {@link #TOOL_CALL_COMPLETED}（Turn 内原子动作）</li>
 *   <li><b>Review 级</b>：{@link #REVIEW_STARTED} / {@link #REVIEW_APPROVED} /
 *       {@link #REVIEW_REJECTED} / {@link #REWORK_STARTED}</li>
 * </ul>
 * </p>
 *
 * <p>事件仅 write-only（append-only 轨迹），不参与任何业务决策（B2 埋点纪律）。</p>
 */
public enum AgentEventType {

    RUN_CREATED("run_created"),
    TASK_CREATED("task_created"),
    TASK_ASSIGNED("task_assigned"),
    AGENT_STARTED("agent_started"),
    SKILL_RESOLVED("skill_resolved"),
    TOOL_RESOLVED("tool_resolved"),
    ENVIRONMENT_RESOLVED("environment_resolved"),
    CONTEXT_BUILT("context_built"),
    TOOL_CALL_STARTED("tool_call_started"),
    TOOL_CALL_COMPLETED("tool_call_completed"),
    AGENT_COMPLETED("agent_completed"),
    REVIEW_STARTED("review_started"),
    REVIEW_REJECTED("review_rejected"),
    REWORK_STARTED("rework_started"),
    REVIEW_APPROVED("review_approved"),
    RUN_COMPLETED("run_completed");

    private final String code;

    AgentEventType(String code) {
        this.code = code;
    }

    /** 数据库存储值（snake_case 字符串）。 */
    public String code() {
        return code;
    }
}