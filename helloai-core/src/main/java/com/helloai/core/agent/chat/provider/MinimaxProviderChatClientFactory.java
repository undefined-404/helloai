package com.helloai.core.agent.chat.provider;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.entity.Agent;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * MiniMax Provider 工厂：走 MiniMax 官方的 Anthropic 兼容接口。
 *
 * <p>参考 springai 项目 MinimaxClientsConfig 的接入方式（AnthropicChatModel +
 * https://api.minimaxi.com/anthropic），AnthropicApi 在 base-url 后拼接
 * /v1/messages。与 DeepSeek 工厂保持同款缓存 / 超时 / 重试 / 观测策略。</p>
 */
@Component
@RequiredArgsConstructor
public class MinimaxProviderChatClientFactory implements ProviderChatClientFactory {

    private static final String PROVIDER = "minimax";
    private static final String DEFAULT_MODEL = "MiniMax-M2.5";
    private static final String DEFAULT_BASE_URL = "https://api.minimaxi.com/anthropic";

    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final AgentProviderProperties providerProperties;
    private final ProviderChatModelCache cache;

    @Override
    public boolean supports(String provider) {
        return PROVIDER.equalsIgnoreCase(provider);
    }

    @Override
    public ChatClient createChatClient(String apiKeyPlaintext, Agent agent, String model) {
        if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }

        AgentProviderProperties.ProviderConfig config = providerProperties.getConfig(PROVIDER);

        String cacheKey = ProviderChatModelCache.buildKey(PROVIDER, apiKeyPlaintext, config.getBaseUrl());

        ChatModel chatModel = cache.getOrCompute(cacheKey, () -> buildChatModel(apiKeyPlaintext, config, model));
        return ChatClient.create(chatModel);
    }

    private ChatModel buildChatModel(String apiKeyPlaintext,
                                     AgentProviderProperties.ProviderConfig config,
                                     String requestedModel) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(config.getConnectTimeoutMs(), 1));
        requestFactory.setReadTimeout(Math.max(config.getReadTimeoutMs(), 1));

        String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl()
                : DEFAULT_BASE_URL;

        AnthropicApi anthropicApi = AnthropicApi.builder()
                .apiKey(apiKeyPlaintext)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        String effectiveModel = requestedModel != null && !requestedModel.isBlank()
                ? requestedModel
                : (config.getDefaultModel() != null && !config.getDefaultModel().isBlank()
                        ? config.getDefaultModel()
                        : DEFAULT_MODEL);

        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(effectiveModel)
                .build();

        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }
}
