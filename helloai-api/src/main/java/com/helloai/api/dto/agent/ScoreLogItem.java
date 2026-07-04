package com.helloai.api.dto.agent;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ScoreLogItem {
    private Long id;
    private Long agentId;
    private Long subTaskId;
    private String reason;
    private Integer delta;
    private Integer balance;
    private OffsetDateTime createTime;
}
