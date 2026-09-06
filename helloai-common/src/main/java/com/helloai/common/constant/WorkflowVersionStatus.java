package com.helloai.common.constant;

/**
 * Workflow 模板版本状态（N-001，C1 设计）。
 *
 * <p>发布后不可变：历史实例绑定版本快照，模板改版需创建新版本（D4）。</p>
 */
public enum WorkflowVersionStatus {

    /** 草稿：可编辑。 */
    DRAFT,

    /** 已发布：不可再编辑，可被实例化引用。 */
    PUBLISHED
}
