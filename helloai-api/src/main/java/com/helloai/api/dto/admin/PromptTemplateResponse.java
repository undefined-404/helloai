package com.helloai.api.dto.admin;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class PromptTemplateResponse {
    private Long id;
    private String role;
    private String name;
    private String content;
    private Integer isDefault;
    private Integer version;
    private String remark;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
