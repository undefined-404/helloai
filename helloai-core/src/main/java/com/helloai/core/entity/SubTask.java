package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.SubTaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sub_task")
public class SubTask extends BaseEntity {

    private Long taskId;
    private Long moduleId;
    private String title;

    @TableField("status")
    private SubTaskStatus status;

    private Long assignedAgent;
    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> context;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> scoreFactors;

    private Integer compositeScore;
    private String scoreGrade;
    private OffsetDateTime deadline;

    @Version
    private Integer version;

    private Integer timeoutCount;
}
