package com.helloai.core.agent.chat.provider;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.chat.PlatformProviderConfigService;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.entity.LlmProvider;
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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容协议通用工厂（方案B）。
 *
 * <p>任何 {@code protocol_type=OPENAI_COMPATIBLE} 的 Provider 都通过本工厂创建 ChatClient。
 * 替代原 MoonshotProviderChatClientFactory / DashScopeProviderChatClientFactory，
 * 以及新增的自定义 OpenAI 兼容供应商场景。</p>
 *
 * <p>deepseek 仍走专用 {@link DeepSeekProviderChatClientFactory}（官方 SDK），不在本工厂处理。</p>
 */
@Component
@RequiredArgsConstructor
public class OpenAiCompatibleProtocolFactory implements LlmProviderChatClientFactoryRegistry.ProtocolFactory {

    private static final String PROTOCOL_TYPE = "OPENAI_COMPATIBLE";

    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final ProviderChatModelCache cache;
    private final AgentProviderProperties providerProperties;
    private final PlatformProviderConfigService platformProviderConfigService;

    @Override
    public String protocolType() {
        return PROTOCOL_TYPE;
    }

    @Override
    public ChatClient createChatClient(LlmProvider provider, String apiKeyPlaintext, Agent agent, String model) {
        if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }
        String baseUrl = platformProviderConfigService.getBaseUrl(provider.getProviderCode());
        String cacheKey = ProviderChatModelCache.buildKey(
                provider.getProviderCode(), apiKeyPlaintext, baseUrl, PROTOCOL_TYPE);

        ChatModel chatModel = cache.getOrCompute(cacheKey,
                () -> buildChatModel(provider, apiKeyPlaintext, baseUrl, model));
        return ChatClient.create(chatModel);
    }

    private ChatModel buildChatModel(LlmProvider provider,
                                     String apiKey,
                                     String baseUrl,
                                     String requestedModel) {
        AgentProviderProperties.ProviderConfig config =
                providerProperties.getConfig(provider.getProviderCode());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(config.getConnectTimeoutMs(), 1));
        requestFactory.setReadTimeout(Math.max(config.getReadTimeoutMs(), 1));

        String effectiveBaseUrl = baseUrl != null && !baseUrl.isBlank()
                ? baseUrl
                : provider.getBaseUrl();

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(effectiveBaseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        // defaultModel 优先级：调用方传入 > provider.defaultModel > 兜底
        String effectiveModel = requestedModel != null && !requestedModel.isBlank()
                ? requestedModel
                : (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()
                        ? provider.getDefaultModel()
                        : platformProviderConfigService.getDefaultModel(provider.getProviderCode()));

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
