package com.helloai.common.constant;

/**
 * Workflow 模板状态（N-001，C1 设计）。
 */
public enum WorkflowTemplateStatus {

    /** 草稿：可编辑，未发布任何版本。 */
    DRAFT,

    /** 生效：已发布至少一个版本（current_version_id 非空）。 */
    ACTIVE,

    /** 归档：不再用于新实例化。 */
    ARCHIVED
}
