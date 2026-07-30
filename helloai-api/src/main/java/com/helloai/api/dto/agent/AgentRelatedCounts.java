package com.helloai.api.dto.agent;

import lombok.Data;

@Data
public class AgentRelatedCounts {
    private Long agentId;
    private String agentName;
    private int subTaskCount;
    private int reviewCount;
    private int rewardCount;
    private int activityCount;
}
