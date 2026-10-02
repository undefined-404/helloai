package com.helloai.common.constant;

/**
 * Task-Team 成员入队来源。
 *
 * <p>记录 agent 是以什么方式进入某个任务的团队（授权边界）。四类运行时入口 + 一类重建兜底，
 * 与 {@code task_agent_member.join_source} 列的 CHECK 约束一一对应。</p>
 *
 * <p>语义要点：<b>加入过即成员</b>——改派不写 {@code leave_time}、不置 LEFT，
 * 故同一 agent 在同一任务内只会有一条成员记录（{@code uk(task_id, agent_id)}），
 * {@code join_source} 保留<b>首次入队</b>来源；仅当原值为 {@link #REBUILT}（无法观测真实入口）
 * 时才被真实入口升级覆盖。</p>
 */
public enum TaskMemberJoinSource {

    /** 任务拆解分配子任务时入队。 */
    ASSIGNED,

    /** 外部 agent 通过 MCP {@code claimSubTask} 认领时入队。 */
    CLAIMED,

    /** 改派（{@code redispatchInProgress} / {@code dispatchBlockedSubTask}）时入队。 */
    REASSIGNED,

    /** 人工登记（预留：提前授权"待命成员"，当前无入口）。 */
    MANUAL,

    /** 启动一次性重建对账补齐（无法观测真实入队入口时的兜底来源）。 */
    REBUILT
}
