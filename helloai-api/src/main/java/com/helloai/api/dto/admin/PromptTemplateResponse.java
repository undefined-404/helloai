package com.helloai.api.dto.admin;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class PromptTemplateResponse {
    private Long id;
    private String role;
    private String category;
    private String slug;
    private String name;
    private String description;
    private String content;
    private Integer isDefault;
    private Integer isExample;
    private Integer version;
    private String remark;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
