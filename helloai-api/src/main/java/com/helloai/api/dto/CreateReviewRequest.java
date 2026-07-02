package com.helloai.api.dto;

import lombok.Data;

@Data
public class CreateReviewRequest {
    private Long subTaskId;
    private String result;
    private Integer score;
    private String issues;
    private String comment;
    private Long reworkAgentId;
}
