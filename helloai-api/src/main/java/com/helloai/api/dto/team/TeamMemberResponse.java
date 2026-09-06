package com.helloai.api.dto.team;

import lombok.Data;

/**
 * Team 成员响应（N-002，C2-S1）。
 */
@Data
public class TeamMemberResponse {

    private Long id;
    private Long teamId;
    private Long agentId;
    private String slotRole;
    private Integer weight;
}
