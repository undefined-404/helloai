package com.helloai.api.dto.agent;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import lombok.Data;

/**
 * Agent LLM 连通性检测响应。
 */
@Data
public class AgentExecutionConnectivityResponse {

    private Long agentId;
    private String agentName;
    private AgentRole role;
    private AgentAccessType accessType;
    private String provider;
    private String model;
    private boolean mockMode;
    private boolean hasActiveVaultCredential;
    private boolean hasEncryptedValue;
    private boolean hasSecretRef;
    private boolean credentialReady;
    private boolean success;
    private String stage;
    private Long latencyMs;
    private String output;
    private String errorMessage;
    private String rootException;
    private String rootMessage;
    private Integer tokenUsage;
}
