package com.helloai.api.dto.workflow;

import lombok.Data;

import java.util.Map;

/**
 * Workflow 模板版本创建请求（N-001，C1-S1）。
 *
 * <p>definition JSONB 结构见 {@code WorkflowTemplateVersion} 注释；服务端经
 * {@code WorkflowDefinitionValidator} 校验（节点唯一/角色白名单/依赖引用/DAG 无环）。</p>
 */
@Data
public class WorkflowVersionRequest {

    /** 定义 JSONB（节点集 + 依赖边 + paramsSchema）。 */
    private Map<String, Object> definition;
}
