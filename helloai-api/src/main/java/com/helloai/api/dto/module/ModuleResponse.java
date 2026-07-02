package com.helloai.api.dto.module;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ModuleResponse {
    private Long id;
    private Long taskId;
    private String name;
    private String description;
    private Integer sortOrder;
    private OffsetDateTime createTime;
}
