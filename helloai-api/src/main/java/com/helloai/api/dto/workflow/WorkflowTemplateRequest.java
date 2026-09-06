package com.helloai.api.dto.workflow;

import lombok.Data;

/**
 * Workflow 模板创建/更新请求（N-001，C1-S1）。
 */
@Data
public class WorkflowTemplateRequest {

    /** 模板名称（必填）。 */
    private String name;

    /** 描述。 */
    private String description;

    /** 分类（可选）。 */
    private String category;
}
