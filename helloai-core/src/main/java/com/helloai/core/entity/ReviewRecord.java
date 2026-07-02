package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.ReviewResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("review_record")
public class ReviewRecord extends BaseEntity {

    private Long subTaskId;
    private Long reviewerAgent;
    private ReviewResult result;
    private Integer score;
    private String issues;
    private String comment;
    private Integer round;
}
