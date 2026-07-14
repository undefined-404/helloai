package com.helloai.start.chat;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.chat.ProviderChatClientFactory;
import com.helloai.core.entity.Agent;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
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

        DeepSeekApi.Builder apiBuilder = DeepSeekApi.builder().apiKey(apiKeyPlaintext);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(config.getConnectTimeoutMs(), 1));
        requestFactory.setReadTimeout(Math.max(config.getReadTimeoutMs(), 1));
        apiBuilder.restClientBuilder(RestClient.builder().requestFactory(requestFactory));
        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            apiBuilder.baseUrl(config.getBaseUrl());
        }

        String effectiveModel = model != null && !model.isBlank()
                ? model
                : (config.getDefaultModel() != null && !config.getDefaultModel().isBlank()
                        ? config.getDefaultModel()
                        : DEFAULT_MODEL);

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(effectiveModel)
                .build();

        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(apiBuilder.build())
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();

        return ChatClient.create(chatModel);
    }
}
