package com.helloai.core.task.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.WorkflowVersionStatus;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * Workflow 模板版本（N-001，C1 设计）。
 *
 * <p>不可变版本快照：{@link #definition} 存节点集 + 依赖边 + paramsSchema
 * （发布后不可编辑，历史实例绑定本快照）。</p>
 *
 * <p>definition 结构（C1 D2/D5，应用层校验见 WorkflowDefinitionValidator）：</p>
 * <pre>{@code
 * {
 *   "nodes": [
 *     {"nodeKey": "contract", "role": "executor",
 *      "spec": {"goal": "...", "definition_of_done": "...", "estimated_effort": 1},
 *      "constraints": {"skills": ["eng-doc-standard"], "access_type": "..."},
 *      "dependsOn": []}
 *   ],
 *   "paramsSchema": {"business_area": {"type": "string", "required": true}}
 * }
 * }</pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_template_version")
public class WorkflowTemplateVersion extends BaseEntity {

    /** 所属模板 ID。 */
    private Long templateId;

    /** 版本号（模板内递增 1, 2, 3...）。 */
    private Integer versionNo;

    /** 定义 JSONB（节点集 + 依赖边 + paramsSchema）。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> definition;

    /** 状态：DRAFT / PUBLISHED（发布后不可变）。 */
    private WorkflowVersionStatus status;
}
