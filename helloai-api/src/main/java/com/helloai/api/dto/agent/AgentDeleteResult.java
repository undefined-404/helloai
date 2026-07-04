package com.helloai.api.dto.agent;

import lombok.Data;

@Data
public class AgentDeleteResult {
    private String agentName;
    private int subTaskCount;
    private int reviewCount;
    private int rewardCount;
    private int activityCount;
    private int patrolCount;
}
