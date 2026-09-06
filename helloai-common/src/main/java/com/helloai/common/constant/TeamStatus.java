package com.helloai.common.constant;

/**
 * Team 生命周期状态（N-002，C2-S1）。
 *
 * <p>{@code team.status} 取值：DRAFT（草稿，可自由编辑成员）/ ACTIVE（已发布，
 * 作为 agent_policy.teamId 的展开源，成员变更不影响已建任务快照）/ ARCHIVED（停用归档）。</p>
 */
public enum TeamStatus {
    /** 草稿：可自由编辑名称/描述/成员。 */
    DRAFT,
    /** 已发布：可作为任务 agent_policy.teamId 的展开源。 */
    ACTIVE,
    /** 已归档：不可再编辑与发布。 */
    ARCHIVED
}
