package com.helloai.core.agent.chat.provider;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.entity.Agent;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容接口的 ProviderChatClientFactory 公共骨架。
 *
 * <p>moonshot（Kimi）与 dashscope（通义千问 compatible-mode）均暴露 OpenAI 协议的
 * /v1/chat/completions 端点，仅 base-url 与默认模型不同，故收敛到本基类；子类只需
 * 提供 provider 标识、默认模型与兜底 base-url。构建逻辑与
 * {@link DeepSeekProviderChatClientFactory} 保持同构（缓存 key、超时、重试、观测）。</p>
 */
@RequiredArgsConstructor
public abstract class AbstractOpenAiCompatibleProviderChatClientFactory implements ProviderChatClientFactory {

    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final AgentProviderProperties providerProperties;
    private final ProviderChatModelCache cache;

    /** provider 标识（与 helloai.providers 下的 key 对应，如 moonshot / dashscope）。 */
    protected abstract String provider();

    /** yml 未配置 default-model 时的兜底模型名。 */
    protected abstract String defaultModel();

    /** yml 未配置 base-url 时的兜底地址（OpenAiApi 会在其后拼接 /v1/chat/completions）。 */
    protected abstract String defaultBaseUrl();

    @Override
    public boolean supports(String provider) {
        return provider().equalsIgnoreCase(provider);
    }

    @Override
    public ChatClient createChatClient(String apiKeyPlaintext, Agent agent, String model) {
        if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }

        AgentProviderProperties.ProviderConfig config = providerProperties.getConfig(provider());

        // 与 DeepSeek 工厂同款缓存策略：按 (provider, baseUrl, apiKey) 三元组复用 ChatModel
        String cacheKey = ProviderChatModelCache.buildKey(provider(), apiKeyPlaintext, config.getBaseUrl());

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
                : defaultBaseUrl();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKeyPlaintext)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        String effectiveModel = requestedModel != null && !requestedModel.isBlank()
                ? requestedModel
                : (config.getDefaultModel() != null && !config.getDefaultModel().isBlank()
                        ? config.getDefaultModel()
                        : defaultModel());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(effectiveModel)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }
}
