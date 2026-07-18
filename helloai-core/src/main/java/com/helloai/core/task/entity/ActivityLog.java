package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("activity_log")
public class ActivityLog extends BaseEntity {

    private Long agentId;
    private Long subTaskId;
    private String action;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detail;

    private String level;
    private String source;
}
