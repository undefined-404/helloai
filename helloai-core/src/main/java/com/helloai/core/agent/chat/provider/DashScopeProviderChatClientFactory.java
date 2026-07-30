package com.helloai.core.agent.chat.provider;

import com.helloai.common.config.AgentProviderProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

/**
 * DashScope（阿里云通义千问）Provider 工厂：走 DashScope 的 OpenAI 兼容模式。
 *
 * <p>springai 项目使用 spring-ai-alibaba 专属 starter（绑定 spring-ai 1.0.0），
 * 与本项目 spring-ai 1.1.8 基线存在版本冲突风险，故改用官方 compatible-mode
 * 端点复用 OpenAiChatModel（OpenAiApi 在 base-url 后拼接 /v1/chat/completions，
 * 完整地址为 https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions）。</p>
 */
@Component
public class DashScopeProviderChatClientFactory extends AbstractOpenAiCompatibleProviderChatClientFactory {

    private static final String PROVIDER = "dashscope";
    private static final String DEFAULT_MODEL = "qwen-plus";
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";

    public DashScopeProviderChatClientFactory(ToolCallingManager toolCallingManager,
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
