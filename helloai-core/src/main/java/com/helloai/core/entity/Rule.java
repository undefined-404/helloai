package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("rule")
public class Rule extends BaseEntity {

    private String name;
    private String ruleType;
    private Integer priority;
    private String content;
}
