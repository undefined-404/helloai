package com.helloai.api.dto.review;

import com.helloai.common.constant.ReviewResult;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ReviewResponse {
    private Long id;
    private Long subTaskId;
    private Long reviewerAgent;
    private ReviewResult result;
    private Integer score;
    private String issues;
    private String comment;
    private Integer round;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
    private String remark;
}
