package com.helloai.api.dto.agent;

import lombok.Data;

/**
 * Agent LLM 连通性检测请求。
 */
@Data
public class AgentExecutionConnectivityRequest {

    private String systemPrompt;
    private String userPrompt;
}
