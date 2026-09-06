package com.helloai.core.task.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.WorkflowTemplateStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Workflow 模板（N-001，C1 设计）。
 *
 * <p>可复用蓝图的元信息：名称/描述/分类/状态/当前激活版本。节点图本体在
 * {@link WorkflowTemplateVersion#getDefinition()}（不可变版本快照）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_template")
public class WorkflowTemplate extends BaseEntity {

    /** 模板名称。 */
    private String name;

    /** 描述。 */
    private String description;

    /** 状态：DRAFT / ACTIVE / ARCHIVED。 */
    private WorkflowTemplateStatus status;

    /** 当前激活版本（软引用 workflow_template_version.id，发布版本时回填）。 */
    private Long currentVersionId;

    /** 分类（可选，如 dev-release / data-etl）。 */
    private String category;
}
