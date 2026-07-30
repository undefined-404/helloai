package com.helloai.core.agent.chat;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.chat.provider.ProviderChatClientFactory;
import com.helloai.core.agent.entity.Agent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Agent ChatClient 服务。
 *
 * <p>T4 先以稳定 mock 模式接入 Spring AI ChatClient，确保平台内执行链路可在本地稳定验证。
 * 后续切真实 Provider 时，复用这里的 ChatClient 组装入口即可。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentChatClientService {

    private final AgentExecutionProperties executionProperties;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final ObjectProvider<java.util.List<ProviderChatClientFactory>> providerChatClientFactoriesProvider;

    /**
     * 使用 Spring AI ChatClient 生成回复。
     */
    public ChatResponse generate(Agent agent, String systemPrompt, String userPrompt) {
        return generate(agent, systemPrompt, userPrompt, null, null);
    }

    public ChatResponse generate(Agent agent, String systemPrompt, String userPrompt,
                                 String provider, String apiKeyPlaintext) {
        if (!executionProperties.isEnabled()) {
            throw new BizException("平台内 Agent 执行链已关闭");
        }
        ChatClient chatClient;
        if (executionProperties.isMockMode()) {
            chatClient = ChatClient.create(new MockChatModel(
                    executionProperties.getProvider(),
                    executionProperties.getModel(),
                    executionProperties.getMockResponsePrefix(),
                    agent));
        } else {
            if (apiKeyPlaintext != null && !apiKeyPlaintext.isBlank()) {
                String effectiveProvider = provider != null && !provider.isBlank()
                        ? provider : executionProperties.getProvider();
                String model = AgentProviderResolver.resolveModel(agent, null);
                java.util.List<ProviderChatClientFactory> factories =
                        providerChatClientFactoriesProvider.getIfAvailable(java.util.List::of);
                ProviderChatClientFactory factory = factories.stream()
                        .filter(f -> f.supports(effectiveProvider))
                        .findFirst()
                        .orElseThrow(() -> new BizException("未找到 ProviderChatClientFactory: " + effectiveProvider));
                chatClient = factory.createChatClient(apiKeyPlaintext, agent, model);
            } else {
                ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
                if (builder == null) {
                    throw new BizException("未检测到 ChatClient.Builder，请接入 Spring AI Provider starter 并配置 API Key");
                }
                chatClient = builder.build();
            }
        }

        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt();
        if (StringUtils.hasText(systemPrompt)) {
            prompt = prompt.system(systemPrompt);
        }
        return prompt
                .user(userPrompt != null ? userPrompt : "")
                .call()
                .chatResponse();
    }

    /**
     * T4 最小 mock ChatModel。
     */
    private static final class MockChatModel implements ChatModel {

        private final String provider;
        private final String model;
        private final String prefix;
        private final Agent agent;

        private MockChatModel(String provider, String model, String prefix, Agent agent) {
            this.provider = provider;
            this.model = model;
            this.prefix = prefix;
            this.agent = agent;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String userText = prompt.getUserMessage() != null ? prompt.getUserMessage().getText() : "";
            String systemText = prompt.getSystemMessage() != null ? prompt.getSystemMessage().getText() : "";
            String content = String.format(
                    "%s agent=%s role=%s model=%s%nSYSTEM:%s%nUSER:%s",
                    prefix,
                    agent.getName(),
                    agent.getRole(),
                    model,
                    abbreviate(systemText),
                    abbreviate(userText)
            );
            int promptTokens = Math.max(1, (systemText.length() + userText.length()) / 4);
            int completionTokens = Math.max(1, content.length() / 4);

            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(content))),
                    ChatResponseMetadata.builder()
                            .model(provider + ":" + model)
                            .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                            .build()
            );
        }

        private String abbreviate(String value) {
            if (value == null || value.isBlank()) {
                return "(empty)";
            }
            return value.length() <= 240 ? value : value.substring(0, 240) + "...";
        }
    }
}
