package com.helloai.api.dto.workflow;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Workflow 模板响应（N-001，C1-S1）。
 */
@Data
public class WorkflowTemplateResponse {

    private Long id;
    private String name;
    private String description;
    private String status;
    private Long currentVersionId;
    private String category;
    private OffsetDateTime createTime;
    private OffsetDateTime updateTime;
}
