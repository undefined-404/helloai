package com.helloai.start.chat;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.chat.ProviderChatClientFactory;
import com.helloai.core.entity.Agent;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class DeepSeekProviderChatClientFactory implements ProviderChatClientFactory {

    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;

    @Value("${spring.ai.deepseek.base-url:}")
    private String baseUrl;

    @Value("${spring.ai.deepseek.chat.completions-path:}")
    private String completionsPath;

    @Value("${spring.ai.deepseek.http.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${spring.ai.deepseek.http.read-timeout-ms:60000}")
    private int readTimeoutMs;

    @Override
    public boolean supports(String provider) {
        return provider != null && provider.equalsIgnoreCase("deepseek");
    }

    @Override
    public ChatClient createChatClient(String apiKeyPlaintext, Agent agent, String model) {
        if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
            throw new BizException("apiKey 不能为空");
        }

        DeepSeekApi.Builder apiBuilder = DeepSeekApi.builder().apiKey(apiKeyPlaintext);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(connectTimeoutMs, 1));
        requestFactory.setReadTimeout(Math.max(readTimeoutMs, 1));
        apiBuilder.restClientBuilder(RestClient.builder().requestFactory(requestFactory));
        if (baseUrl != null && !baseUrl.isBlank()) {
            apiBuilder.baseUrl(baseUrl);
        }
        if (completionsPath != null && !completionsPath.isBlank()) {
            apiBuilder.completionsPath(completionsPath);
        }

        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model != null && !model.isBlank() ? model : "deepseek-chat")
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
