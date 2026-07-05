package com.helloai.api.dto.agent;

import lombok.Data;

/**
 * Agent 接入内容生成响应。
 * 包含动态生成的完整接入文本和纯 SKILL 内容，供管理员一键复制给外部 Agent 使用。
 */
@Data
public class AgentOnboardingResponse {
    private Long agentId;
    private String agentName;
    private String role;
    private String apiKey;
    private String baseUrl;
    private String title;
    private String content;
    private String skillContent;
}
