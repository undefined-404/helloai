package com.helloai.start.chat;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.chat.ProviderChatClientFactory;
import com.helloai.core.agent.chat.ProviderChatModelCache;
import com.helloai.core.agent.entity.Agent;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class DeepSeekProviderChatClientFactory implements ProviderChatClientFactory {

    private static final String PROVIDER = "deepseek";
    private static final String DEFAULT_MODEL = "deepseek-chat";

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

        // N9: 按 (provider, baseUrl, apiKey) 三元组复用 ChatModel 实例，避免每次请求重
        // 建 RestClient/连接池。idempotencyKey / 上下文 model 切换由 ChatClient 层的
        // options/advisor 覆盖，不影响本缓存 key。
        String cacheKey = ProviderChatModelCache.buildKey(PROVIDER, apiKeyPlaintext, config.getBaseUrl());

        ChatModel chatModel = cache.getOrCompute(cacheKey, () -> buildChatModel(apiKeyPlaintext, config, model));
        return ChatClient.create(chatModel);
    }

    /**
     * 仅供 {@link #createChatClient} 内部缓存未命中时调用：构造并初始化 DeepSeekChatModel。
     * 与原有逻辑等价，只是拆出来便于测试覆盖。
     */
    private ChatModel buildChatModel(String apiKeyPlaintext,
                                     AgentProviderProperties.ProviderConfig config,
                                     String requestedModel) {
        DeepSeekApi.Builder apiBuilder = DeepSeekApi.builder().apiKey(apiKeyPlaintext);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(config.getConnectTimeoutMs(), 1));
        requestFactory.setReadTimeout(Math.max(config.getReadTimeoutMs(), 1));
        apiBuilder.restClientBuilder(RestClient.builder().requestFactory(requestFactory));
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            apiBuilder.baseUrl(config.getBaseUrl());
        }

        String effectiveModel = requestedModel != null && !requestedModel.isBlank()
                ? requestedModel
                : (config.getDefaultModel() != null && !config.getDefaultModel().isBlank()
                        ? config.getDefaultModel()
                        : DEFAULT_MODEL);

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(effectiveModel)
                .build();

        return DeepSeekChatModel.builder()
                .deepSeekApi(apiBuilder.build())
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }
}
