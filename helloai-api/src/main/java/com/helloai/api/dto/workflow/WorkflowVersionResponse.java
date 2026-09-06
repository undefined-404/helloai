package com.helloai.api.dto.workflow;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Workflow 模板版本响应（N-001，C1-S1）。
 *
 * <p>不含 definition 正文（definition 查询独立提供，避免列表响应体积膨胀）。</p>
 */
@Data
public class WorkflowVersionResponse {

    private Long id;
    private Long templateId;
    private Integer versionNo;
    private String status;
    private OffsetDateTime createTime;
}
