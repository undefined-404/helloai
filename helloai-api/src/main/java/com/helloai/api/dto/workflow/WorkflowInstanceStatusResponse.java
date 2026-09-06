package com.helloai.api.dto.workflow;

import lombok.Data;

import java.util.Map;

/**
 * Workflow 实例聚合状态响应（N-001，C1-S3）。
 */
@Data
public class WorkflowInstanceStatusResponse {

    private Long instanceId;
    private Long taskId;
    private String status;
    private int doneCount;
    private int totalCount;
    private String reason;
    private Map<String, String> nodeStatuses;
}
