package com.helloai.api.dto.agent;

import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AgentListItemVO {
    private Long id;
    private String name;
    private AgentRole role;
    private String description;
    private AgentStatus status;
    private Integer totalScore;
    private Integer rank;

    /* workload */
    private int assignedCount;
    private int inProgressCount;
    private int doneCount;
    private int blockedCount;
    private int reviewCount;

    /* timeline */
    private OffsetDateTime lastRequestAt;
    private OffsetDateTime lastActivityAt;
    private OffsetDateTime createdAt;
}
