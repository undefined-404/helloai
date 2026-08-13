package com.helloai.core.agent.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.domain.AgentExecutionConnectivityResult;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.service.CredentialVaultService;
import com.helloai.core.system.service.CredentialVaultBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Service;
import com.helloai.core.agent.service.AgentChatClientService;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;

/**
 * Agent LLM 连通性验证服务。
 *
 * <p>该服务只验证 vault、provider 与 ChatClient 的最小真实调用链，
 * 不写入 sub_task、execution record 或 timeline，便于快速切分问题边界。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentExecutionConnectivityService {

    private static final String DEFAULT_SYSTEM_PROMPT = "You are a HelloAI connectivity probe. "
            + "Reply with a short confirmation only.";
    private static final String DEFAULT_USER_PROMPT = "Please reply with OK and your model identifier.";

    private final AgentService agentService;
    private final AgentChatClientService agentChatClientService;
    private final CredentialVaultService credentialVaultService;
    private final CredentialVaultBindingService credentialVaultBindingService;
    private final AgentExecutionProperties executionProperties;

    /**
     * 执行一次最小 LLM 连通性验证。
     */
    public AgentExecutionConnectivityResult probe(Long agentId, String systemPrompt, String userPrompt) {
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }

        String provider = resolveProvider(agent);
        String model = resolveModel(agent);
        CredentialVault activeVault = credentialVaultService.getActiveAgentApiKey(agentId, provider);
        AgentExecutionConnectivityResult.AgentExecutionConnectivityResultBuilder builder =
                AgentExecutionConnectivityResult.builder()
                        .agentId(agent.getId())
                        .agentName(agent.getName())
                        .role(agent.getRole())
                        .accessType(agent.getAccessType())
                        .provider(provider)
                        .model(model)
                        .mockMode(executionProperties.isMockMode())
                        .hasActiveVaultCredential(activeVault != null)
                        .hasEncryptedValue(activeVault != null
                                && activeVault.getEncryptedValue() != null
                                && !activeVault.getEncryptedValue().isBlank())
                        .hasSecretRef(activeVault != null
                                && activeVault.getSecretRef() != null
                                && !activeVault.getSecretRef().isBlank());

        long startedAt = System.nanoTime();
        String stage = "init";
        try {
            if (agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
                return fail(builder, startedAt, "access_type_check",
                        "仅支持 API_KEY_LLM 连通性验证，当前类型: " + agent.getAccessType(), null);
            }

            String vaultApiKey = null;
            if (!executionProperties.isMockMode()) {
                stage = "vault_fetch";
                vaultApiKey = credentialVaultBindingService.getAgentApiKeyPlaintext(agentId, provider);
                builder.credentialReady(vaultApiKey != null && !vaultApiKey.isBlank());
                if (vaultApiKey == null || vaultApiKey.isBlank()) {
                    return fail(builder, startedAt, stage,
                            "未取到可用的托管 API Key: agentId=" + agentId + ", provider=" + provider, null);
                }
            } else {
                builder.credentialReady(true);
            }

            stage = "chat_call";
            ChatResponse response = agentChatClientService.generate(
                    agent,
                    hasText(systemPrompt) ? systemPrompt : DEFAULT_SYSTEM_PROMPT,
                    hasText(userPrompt) ? userPrompt : DEFAULT_USER_PROMPT,
                    provider,
                    vaultApiKey
            );
            // 分离正文与思考过程：推理模型（如 Minimax M2.5）的 thinking 块不混入 output
            ChatResponseContentExtractor.ExtractedContent extracted = ChatResponseContentExtractor.extract(response);
            Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
            Integer totalTokens = usage != null ? usage.getTotalTokens() : null;
            return builder
                    .success(true)
                    .stage("chat_ok")
                    .latencyMs(elapsedMs(startedAt))
                    .output(extracted.text())
                    .thinking(extracted.thinking().isBlank() ? null : extracted.thinking())
                    .tokenUsage(totalTokens)
                    .build();
        } catch (Exception e) {
            return fail(builder, startedAt, stage, e.getMessage(), e);
        }
    }

    private AgentExecutionConnectivityResult fail(
            AgentExecutionConnectivityResult.AgentExecutionConnectivityResultBuilder builder,
            long startedAt,
            String stage,
            String errorMessage,
            Exception exception) {
        Throwable root = exception;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return builder
                .success(false)
                .stage(stage)
                .latencyMs(elapsedMs(startedAt))
                .errorMessage(errorMessage)
                .rootException(root != null ? root.getClass().getName() : null)
                .rootMessage(root != null ? root.getMessage() : null)
                .build();
    }

    private String resolveProvider(Agent agent) {
        String modelType = agent.getModelType();
        if (hasText(modelType)) {
            int separator = modelType.indexOf(':');
            if (separator > 0) {
                return modelType.substring(0, separator);
            }
            return modelType;
        }
        return executionProperties.getProvider();
    }

    private String resolveModel(Agent agent) {
        String modelType = agent.getModelType();
        if (hasText(modelType)) {
            int separator = modelType.indexOf(':');
            if (separator >= 0 && separator + 1 < modelType.length()) {
                return modelType.substring(separator + 1);
            }
            return modelType;
        }
        return executionProperties.getModel();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
