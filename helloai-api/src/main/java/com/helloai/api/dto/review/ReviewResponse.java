package com.helloai.api.dto.review;

import com.helloai.common.constant.ReviewResult;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ReviewResponse {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long subTaskId;
    @JsonSerialize(using = ToStringSerializer.class)
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
