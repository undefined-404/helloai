package com.helloai.api.dto.team;

import lombok.Data;

/**
 * Team 成员添加请求（N-002，C2-S1）。
 */
@Data
public class TeamMemberRequest {

    /** 成员 Agent ID（必填，须 ACTIVE）。 */
    private Long agentId;

    /** 槽位角色：PLANNER / EXECUTOR / REVIEWER。 */
    private String slotRole;
}
