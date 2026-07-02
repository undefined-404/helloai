package com.helloai.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateReviewRequest {
    @NotNull(message = "子任务ID不能为空")
    private Long subTaskId;

    @NotNull(message = "审查结果不能为空")
    private String result;

    private Integer score;
    private String issues;
    private String comment;
    private Long reworkAgentId;
}
