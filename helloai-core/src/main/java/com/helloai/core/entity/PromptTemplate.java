package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("prompt_template")
public class PromptTemplate extends BaseEntity {

    private String role;
    private String category;
    private String slug;
    private String name;
    private String description;
    private String content;
    private Integer isDefault;
    private Integer isExample;
    private Integer version;
}
