package com.helloai.api.dto.agent;

import lombok.Data;

import java.util.Map;

/**
 * Agent 执行预览请求。
 */
@Data
public class AgentExecutionPreviewRequest {

    private Long subTaskId;
    private String systemPrompt;
    private String userPrompt;
    private Map<String, Object> context;
    private Map<String, Object> requiredCapabilities;
}
