package com.helloai.api.dto.workflow;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Workflow 实例响应（N-001，C1-S2）。
 */
@Data
public class WorkflowInstanceResponse {

    private Long id;
    private Long templateId;
    private Long versionId;
    private Long taskId;
    private Map<String, Object> params;
    private String statusSnapshot;
    private OffsetDateTime startTime;
}
