package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("patrol_record")
public class PatrolRecord extends BaseEntity {

    private Long subTaskId;
    private Long patrolAgent;
    private String alertType;
    private String description;
}
