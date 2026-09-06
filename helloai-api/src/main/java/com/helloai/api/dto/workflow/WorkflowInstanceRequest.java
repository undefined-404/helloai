package com.helloai.api.dto.workflow;

import lombok.Data;

import java.util.Map;

/**
 * Workflow 实例化请求（N-001，C1-S2）。
 */
@Data
public class WorkflowInstanceRequest {

    /** 实例化参数（占位符渲染输入；paramsSchema 必填项须提供）。 */
    private Map<String, Object> params;
}
