package com.helloai.core.agent.chat.provider;

import com.helloai.common.config.AgentProviderProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * Moonshot（Kimi）Provider 工厂：走 OpenAI 兼容接口 /v1/chat/completions。
 *
 * <p>参考 springai 项目 KimiClientsConfig 的接入方式（OpenAiChatModel + moonshot
 * base-url），差异在于本项目按 Agent 凭证动态构建而非启动期固定 Bean。</p>
 */
@Component
public class MoonshotProviderChatClientFactory extends AbstractOpenAiCompatibleProviderChatClientFactory {

    private static final String PROVIDER = "moonshot";
    private static final String DEFAULT_MODEL = "moonshot-v1-8k";
    private static final String DEFAULT_BASE_URL = "https://api.moonshot.cn";

    public MoonshotProviderChatClientFactory(ToolCallingManager toolCallingManager,
                                             RetryTemplate retryTemplate,
                                             ObservationRegistry observationRegistry,
                                             AgentProviderProperties providerProperties,
                                             ProviderChatModelCache cache) {
        super(toolCallingManager, retryTemplate, observationRegistry, providerProperties, cache);
    }

    @Override
    protected String provider() {
        return PROVIDER;
    }

    @Override
    protected String defaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    protected String defaultBaseUrl() {
        return DEFAULT_BASE_URL;
    }
}
