package com.helloai.core.agent.executor;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.AgentLlmCredentialResolver;
import com.helloai.core.agent.chat.AgentProviderResolver;
import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentChatClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * API_KEY_LLM 执行器占位实现。
 *
 * <p>先把平台内执行抽象和路由入口立住，真实 ChatClient 链路放到 /接入。</p>
 */
@Slf4j
@Component
public class ApiKeyAgentExecutor implements AgentExecutor {

    private final AgentChatClientService agentChatClientService;
    private final AgentLlmCredentialResolver agentLlmCredentialResolver;
    private final AgentExecutionProperties executionProperties;

    public ApiKeyAgentExecutor(
            AgentChatClientService agentChatClientService,
            AgentLlmCredentialResolver agentLlmCredentialResolver,
            AgentExecutionProperties executionProperties) {
        this.agentChatClientService = agentChatClientService;
        this.agentLlmCredentialResolver = agentLlmCredentialResolver;
        this.executionProperties = executionProperties;
    }

    @Override
    public AgentResult execute(Agent agent, AgentTask task) {
        final String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());

        String vaultApiKey = resolveApiKey(agent, provider);
        ChatResponse response = agentChatClientService.generate(
                agent,
                task.getSystemPrompt(),
                task.getUserPrompt(),
                provider,
                vaultApiKey,
                task.getTemperature()
        );
        // 分离正文与思考过程：推理模型（如 Minimax ）的 thinking 块不混入 output
        ChatResponseContentExtractor.ExtractedContent extracted = ChatResponseContentExtractor.extract(response);
        String content = extracted.text();
        String thinking = extracted.thinking().isBlank() ? null : extracted.thinking();
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        Integer totalTokens = usage != null ? usage.getTotalTokens() : null;

        log.info("API_KEY_LLM 执行完成: agentId={}, subTaskId={}, modelType={}, tokens={}",
                agent.getId(), task.getSubTaskId(), agent.getModelType(), totalTokens);
        return AgentResult.success(content, thinking, "STOP", getName(), totalTokens);
    }

    /**
     * 解析并校验 API Key（同步与流式共用）：mock 模式返回 null；真实模式优先平台级
     * （模型配置）密钥、Agent 级兜底（系统管理轮换 API Key 后实时生效），
     * 开启 requireVault 且无可用密钥时抛 BizException。
     */
    private String resolveApiKey(Agent agent, String provider) {
        if (executionProperties.isMockMode()) {
            return null;
        }
        String vaultApiKey = agentLlmCredentialResolver.resolveApiKey(agent);
        if (executionProperties.isRequireVault()
                && (vaultApiKey == null || vaultApiKey.isBlank())) {
            throw new BizException("未配置可用的平台级或 Agent 级 API Key: agentId=" + agent.getId()
                                + ", provider=" + provider + "，请先在系统管理中配置模型 API Key");
        }
        return vaultApiKey;
    }

    /**
     * 流式执行：同同步链路的凭证解析（mock/真实一致），LLM 调用走
     * {@link AgentChatClientService#generateStream} 的 token 增量通道。
     *
     * <p>凭证解析与真实 LLM 流都放进 Flux.defer：订阅时才真正发起（惰性语义），
     * 供上层在业务线程池里订阅时异步执行。</p>
     */
    @Override
    public Flux<String> executeStream(Agent agent, AgentTask task) {
        final String provider = AgentProviderResolver.resolveProvider(agent, executionProperties.getProvider());
        return Flux.defer(() -> {
            String vaultApiKey = resolveApiKey(agent, provider);
            return agentChatClientService.generateStream(
                    agent,
                    task.getSystemPrompt(),
                    task.getUserPrompt(),
                    provider,
                    vaultApiKey
            );
        });
    }

    @Override
    public boolean supports(Agent agent) {
        return agent != null && agent.getAccessType() == AgentAccessType.API_KEY_LLM;
    }

}
