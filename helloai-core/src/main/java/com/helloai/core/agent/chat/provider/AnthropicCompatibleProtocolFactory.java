package com.helloai.core.agent.chat.provider;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.entity.LlmProvider;
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
 * Anthropic 兼容协议通用工厂（方案B）。
 *
 * <p>任何 {@code protocol_type=ANTHROPIC_COMPATIBLE} 的 Provider 都通过本工厂创建 ChatClient。
 * 替代原 MinimaxProviderChatClientFactory（minimax 走 Anthropic 兼容 /v1/messages 端点），
 * 以及后续新增的 Anthropic 兼容供应商（如自部署 Claude API 网关）。</p>
 *
 * <p>AnthropicApi 在 base-url 后拼接 /v1/messages。保持与原 Minimax 工厂同款的缓存 / 超时 /
 * 重试 / 观测策略。</p>
 */
@Component
@RequiredArgsConstructor
public class AnthropicCompatibleProtocolFactory implements LlmProviderChatClientFactoryRegistry.ProtocolFactory {

    private static final String PROTOCOL_TYPE = "ANTHROPIC_COMPATIBLE";

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

        AnthropicApi anthropicApi = AnthropicApi.builder()
                .apiKey(apiKey)
                .baseUrl(effectiveBaseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        String effectiveModel = requestedModel != null && !requestedModel.isBlank()
                ? requestedModel
                : (provider.getDefaultModel() != null && !provider.getDefaultModel().isBlank()
                        ? provider.getDefaultModel()
                        : platformProviderConfigService.getDefaultModel(provider.getProviderCode()));

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
