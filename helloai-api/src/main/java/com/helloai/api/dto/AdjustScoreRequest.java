package com.helloai.api.dto;

import lombok.Data;

@Data
public class AdjustScoreRequest {
    private Long agentId;
    private Integer scoreDelta;
    private String reason;
    private Long subTaskId;
}
