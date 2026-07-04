package com.helloai.api.dto.agent;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ActivityLogItem {
    private Long id;
    private Long agentId;
    private Long subTaskId;
    private String action;
    private String summary;
    private String level;
    private OffsetDateTime createTime;
}
