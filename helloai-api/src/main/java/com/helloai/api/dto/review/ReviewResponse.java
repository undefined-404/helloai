package com.helloai.api.dto.review;

import com.helloai.common.constant.ReviewResult;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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
    /** 验收点覆盖清单（`RV-3`）：[{criterionRef,status,method,evidenceRef,note}]；null = 该轮无覆盖清单。 */
    private List<Map<String, String>> coverage;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
    private String remark;
}
