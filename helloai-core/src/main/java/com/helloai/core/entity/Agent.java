package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent")
public class Agent extends BaseEntity {

    private String name;
    private AgentRole role;
    private String apiKey;
    private String modelType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> modelConfig;

    private String specializationSlug;
    private AgentStatus status;
    private Integer score;
}
