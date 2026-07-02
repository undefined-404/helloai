package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent")
public class Agent extends BaseEntity {

    private String name;
    private AgentRole role;
    private String apiKey;
    private String modelType;
    private AgentStatus status;
    private Integer score;
}
