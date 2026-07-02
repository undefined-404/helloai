package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("reward_log")
public class RewardLog extends BaseEntity {

    private Long agentId;
    private Long subTaskId;
    private String reason;
    private Integer delta;
    private Integer balance;
}
