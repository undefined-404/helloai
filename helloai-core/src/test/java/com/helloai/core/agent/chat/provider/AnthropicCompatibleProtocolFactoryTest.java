package com.helloai.core.agent.chat.provider;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.chat.PlatformProviderConfigService;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.entity.LlmProvider;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.retry.support.RetryTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnthropicCompatibleProtocolFactory} 单元测试（C1 Provider 生态补全）。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>apiKey 空白拒绝（BizException）；</li>
 *     <li>Anthropic 兼容 ChatClient 创建成功、ChatModel 类型与 defaultModel 解析；</li>
 *     <li>同 (provider, apiKey, baseUrl) 四元组缓存复用（同实例）；</li>
 *     <li>defaultModel 三级兜底：调用方传入 > llm_provider.defaultModel > sys_config；</li>
 *     <li>baseUrl 兜底：平台配置缺失时回退 llm_provider.baseUrl。</li>
 * </ul>
 */
@DisplayName("AnthropicCompatibleProtocolFactory")
class AnthropicCompatibleProtocolFactoryTest {

    private AnthropicCompatibleProtocolFactory factory;
    private PlatformProviderConfigService platformProviderConfigService;
    private ProviderChatModelCache cache;
    private AgentProviderProperties providerProperties;

    private static final String PROVIDER_CODE = "minimax";

    @BeforeEach
    void setUp() {
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        RetryTemplate retryTemplate = mock(RetryTemplate.class);
        ObservationRegistry observationRegistry = mock(ObservationRegistry.class);
        platformProviderConfigService = mock(PlatformProviderConfigService.class);
        cache = new ProviderChatModelCache();
        providerProperties = new AgentProviderProperties();

        AgentProviderProperties.ProviderConfig config = new AgentProviderProperties.ProviderConfig();
        config.setConnectTimeoutMs(5000);
        config.setReadTimeoutMs(60000);
        providerProperties.getProviders().put(PROVIDER_CODE, config);

        factory = new AnthropicCompatibleProtocolFactory(toolCallingManager, retryTemplate,
                observationRegistry, cache, providerProperties, platformProviderConfigService);
    }

    private LlmProvider llmProvider(String protocolType, String baseUrl, String defaultModel) {
        LlmProvider provider = new LlmProvider();
        provider.setProviderCode(PROVIDER_CODE);
        provider.setProtocolType(protocolType);
        provider.setBaseUrl(baseUrl);
        provider.setDefaultModel(defaultModel);
        return provider;
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(1L);
        return agent;
    }

    @Nested
    @DisplayName("apiKey 校验")
    class ApiKeyValidation {

        @Test
        @DisplayName("null apiKey 抛 BizException")
        void shouldRejectNullApiKey() {
            LlmProvider provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");

            assertThatThrownBy(() -> factory.createChatClient(provider, null, agent(), null))
                    .isInstanceOf(BizException.class)
                    .hasMessage("apiKey 不能为空");
        }

        @Test
        @DisplayName("空白 apiKey 抛 BizException")
        void shouldRejectBlankApiKey() {
            LlmProvider provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");

            assertThatThrownBy(() -> factory.createChatClient(provider, "  ", agent(), null))
                    .isInstanceOf(BizException.class)
                    .hasMessage("apiKey 不能为空");
        }
    }

    @Nested
    @DisplayName("ChatClient 创建")
    class ChatClientCreation {

        @Test
        @DisplayName("创建成功：ChatModel 为 AnthropicChatModel 且 model 用请求值")
        void shouldCreateAnthropicChatModelWithRequestedModel() {
            LlmProvider provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            ChatClient client = factory.createChatClient(provider, "sk-test", agent(), "MiniMax-Text-01");

            assertThat(client).isNotNull();
            ChatModel model = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE"));
            assertThat(model).isInstanceOf(AnthropicChatModel.class);
            assertThat(((AnthropicChatModel) model).getDefaultOptions().getModel())
                    .isEqualTo("MiniMax-Text-01");
        }

        @Test
        @DisplayName("requestedModel 为空时回退 llm_provider.defaultModel，不再查 sys_config")
        void shouldFallbackToProviderDefaultModel() {
            LlmProvider provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            factory.createChatClient(provider, "sk-test", agent(), null);

            ChatModel model = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE"));
            assertThat(((AnthropicChatModel) model).getDefaultOptions().getModel())
                    .isEqualTo("MiniMax-M2.5");
            verify(platformProviderConfigService, never()).getDefaultModel(PROVIDER_CODE);
        }

        @Test
        @DisplayName("defaultModel 也缺省时走 sys_config 兜底")
        void shouldFallbackToSysConfigDefaultModel() {
            LlmProvider provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", null);
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");
            when(platformProviderConfigService.getDefaultModel(PROVIDER_CODE)).thenReturn("MiniMax-M2.5");

            factory.createChatClient(provider, "sk-test", agent(), null);

            ChatModel model = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE"));
            assertThat(((AnthropicChatModel) model).getDefaultOptions().getModel())
                    .isEqualTo("MiniMax-M2.5");
            verify(platformProviderConfigService).getDefaultModel(PROVIDER_CODE);
        }

        @Test
        @DisplayName("平台 baseUrl 缺失时回退 llm_provider.baseUrl，不抛错")
        void shouldFallbackToProviderBaseUrlWhenPlatformConfigMissing() {
            LlmProvider provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn(null);

            ChatClient client = factory.createChatClient(provider, "sk-test", agent(), null);

            assertThat(client).isNotNull();
            assertThat(cache.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("agent.providers 配置段缺失时使用默认超时，不抛错")
        void shouldWorkWithoutYmlConfigSection() {
            providerProperties.getProviders().clear();
            LlmProvider provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            ChatClient client = factory.createChatClient(provider, "sk-test", agent(), null);

            assertThat(client).isNotNull();
            assertThat(cache.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("缓存复用")
    class CacheReuse {

        @Test
        @DisplayName("同四元组二次创建复用同一 ChatModel（ChatClient 包装每次新建）")
        void shouldReuseCachedChatModel() {
            LlmProvider provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            ChatClient first = factory.createChatClient(provider, "sk-test", agent(), "MiniMax-M2.5");
            ChatClient second = factory.createChatClient(provider, "sk-test", agent(), "MiniMax-M2.5");

            assertThat(cache.size()).isEqualTo(1);
            ChatModel firstModel = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE"));
            ChatModel secondModel = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE"));
            assertThat(secondModel).isSameAs(firstModel);
            assertThat(second).isNotSameAs(first);
        }

        @Test
        @DisplayName("不同 apiKey 不共享缓存实例")
        void shouldIsolateByApiKey() {
            LlmProvider provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            factory.createChatClient(provider, "sk-a", agent(), null);
            factory.createChatClient(provider, "sk-b", agent(), null);

            assertThat(cache.size()).isEqualTo(2);
        }
    }
}
