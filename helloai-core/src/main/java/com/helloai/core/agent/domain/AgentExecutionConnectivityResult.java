package com.helloai.core.agent.domain;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import lombok.Builder;
import lombok.Value;

/**
 * Agent LLM 连通性检测结果。
 *
 * <p>用于独立验证 vault -> provider -> ChatClient 的最小真实调用链，
 * 避免在 blocked / redispatch 等长链路里混入过多干扰因素。</p>
 */
@Value
@Builder
public class AgentExecutionConnectivityResult {

    Long agentId;
    String agentName;
    AgentRole role;
    AgentAccessType accessType;
    String provider;
    String model;
    boolean mockMode;
    boolean hasActiveVaultCredential;
    boolean hasEncryptedValue;
    boolean hasSecretRef;
    boolean credentialReady;
    boolean success;
    String stage;
    Long latencyMs;
    String output;
    String errorMessage;
    String rootException;
    String rootMessage;
    Integer tokenUsage;
}
