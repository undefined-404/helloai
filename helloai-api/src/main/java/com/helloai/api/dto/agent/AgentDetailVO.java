package com.helloai.api.dto.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDetailVO extends AgentListItemVO {
    private int totalAgents;
    private int rewardCount;
    private int penaltyCount;
    private int totalRewardRecords;
    private String apiKey;
    private String modelType;
    private String specializationSlug;
}
