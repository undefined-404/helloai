package com.helloai.api.dto.agent;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import lombok.Data;

/**
 * Agent 执行预览响应。
 */
@Data
public class AgentExecutionPreviewResponse {

    private Long agentId;
    private String agentName;
    private AgentRole role;
    private AgentAccessType accessType;
    private String executorName;
    private boolean success;
    private String output;
    private String errorMessage;
    private String finishReason;
    private Integer tokenUsage;
}
