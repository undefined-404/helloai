package com.helloai.core.agent.chat.provider;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.port.LlmProviderProfile;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
 *     <li>同 (provider, apiKey, baseUrl, protocolType, model) 五元组缓存复用（同实例），不同 model 各自建桶；</li>
 *     <li>defaultModel 三级兜底：调用方传入 > llm_provider.defaultModel > sys_config；</li>
 *     <li>baseUrl 兜底：平台配置缺失时回退 llm_provider.baseUrl；</li>
 *     <li>M-5：{@code extraConfig.messagesPath} 能**改变实际请求路径**（本地 HTTP 桩断言，未配置时走默认 /v1/messages）。</li>
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

    private LlmProviderProfile llmProvider(String protocolType, String baseUrl, String defaultModel) {
        return new LlmProviderProfile(PROVIDER_CODE, null, baseUrl, defaultModel, protocolType, 1, null);
    }

    /** M-5：带 {@code extraConfig} 的快照（端点路径覆盖）。 */
    private LlmProviderProfile llmProviderWithExtra(String protocolType, String baseUrl, String defaultModel,
                                                    Map<String, Object> extraConfig) {
        return new LlmProviderProfile(PROVIDER_CODE, null, baseUrl, defaultModel, protocolType, 1, extraConfig);
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
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");

            assertThatThrownBy(() -> factory.createChatClient(provider, null, agent(), null))
                    .isInstanceOf(BizException.class)
                    .hasMessage("apiKey 不能为空");
        }

        @Test
        @DisplayName("空白 apiKey 抛 BizException")
        void shouldRejectBlankApiKey() {
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");

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
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            ChatClient client = factory.createChatClient(provider, "sk-test", agent(), "MiniMax-Text-01");

            assertThat(client).isNotNull();
            ChatModel model = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE", "MiniMax-Text-01"));
            assertThat(model).isInstanceOf(AnthropicChatModel.class);
            assertThat(((AnthropicChatModel) model).getDefaultOptions().getModel())
                    .isEqualTo("MiniMax-Text-01");
        }

        @Test
        @DisplayName("requestedModel 为空时回退 llm_provider.defaultModel，不再查 sys_config")
        void shouldFallbackToProviderDefaultModel() {
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            factory.createChatClient(provider, "sk-test", agent(), null);

            ChatModel model = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE", null));
            assertThat(((AnthropicChatModel) model).getDefaultOptions().getModel())
                    .isEqualTo("MiniMax-M2.5");
            verify(platformProviderConfigService, never()).getDefaultModel(PROVIDER_CODE);
        }

        @Test
        @DisplayName("defaultModel 也缺省时走 sys_config 兜底")
        void shouldFallbackToSysConfigDefaultModel() {
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", null);
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");
            when(platformProviderConfigService.getDefaultModel(PROVIDER_CODE)).thenReturn("MiniMax-M2.5");

            factory.createChatClient(provider, "sk-test", agent(), null);

            ChatModel model = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE", null));
            assertThat(((AnthropicChatModel) model).getDefaultOptions().getModel())
                    .isEqualTo("MiniMax-M2.5");
            verify(platformProviderConfigService).getDefaultModel(PROVIDER_CODE);
        }

        @Test
        @DisplayName("平台 baseUrl 缺失时回退 llm_provider.baseUrl，不抛错")
        void shouldFallbackToProviderBaseUrlWhenPlatformConfigMissing() {
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn(null);

            ChatClient client = factory.createChatClient(provider, "sk-test", agent(), null);

            assertThat(client).isNotNull();
            assertThat(cache.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("agent.providers 配置段缺失时使用默认超时，不抛错")
        void shouldWorkWithoutYmlConfigSection() {
            providerProperties.getProviders().clear();
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
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
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            ChatClient first = factory.createChatClient(provider, "sk-test", agent(), "MiniMax-M2.5");
            ChatClient second = factory.createChatClient(provider, "sk-test", agent(), "MiniMax-M2.5");

            assertThat(cache.size()).isEqualTo(1);
            ChatModel firstModel = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE", "MiniMax-M2.5"));
            ChatModel secondModel = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE", "MiniMax-M2.5"));
            assertThat(secondModel).isSameAs(firstModel);
            assertThat(second).isNotSameAs(first);
        }

        @Test
        @DisplayName("同四元组不同 model 各自建桶，不共享实例（改模型即时生效的缓存基础）")
        void shouldIsolateByModel() {
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            factory.createChatClient(provider, "sk-test", agent(), "MiniMax-Text-01");
            factory.createChatClient(provider, "sk-test", agent(), "MiniMax-M2.5");

            assertThat(cache.size()).isEqualTo(2);
            ChatModel modelText = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE", "MiniMax-Text-01"));
            ChatModel modelM25 = cache.get(ProviderChatModelCache.buildKey(
                    PROVIDER_CODE, "sk-test", "https://api.minimaxi.com/anthropic", "ANTHROPIC_COMPATIBLE", "MiniMax-M2.5"));
            assertThat(modelText).isNotSameAs(modelM25);
            assertThat(((AnthropicChatModel) modelText).getDefaultOptions().getModel()).isEqualTo("MiniMax-Text-01");
            assertThat(((AnthropicChatModel) modelM25).getDefaultOptions().getModel()).isEqualTo("MiniMax-M2.5");
        }

        @Test
        @DisplayName("不同 apiKey 不共享缓存实例")
        void shouldIsolateByApiKey() {
            LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            factory.createChatClient(provider, "sk-a", agent(), null);
            factory.createChatClient(provider, "sk-b", agent(), null);

            assertThat(cache.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("M-5 extraConfig.messagesPath 端点路径覆盖")
    class ExtraConfigEndpointPath {

        /** 桩返回的最小合法 Anthropic message（足以让 AnthropicChatModel 解析出 content）。 */
        private static final String OK_JSON = """
                {"id":"msg_stub","type":"message","role":"assistant","model":"stub",
                 "content":[{"type":"text","text":"pong"}],"stop_reason":"end_turn",
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """;

        /** 真依赖版工厂：只有真实 RetryTemplate / ObservationRegistry / ToolCallingManager 才发得出请求。 */
        private AnthropicCompatibleProtocolFactory realFactory() {
            return new AnthropicCompatibleProtocolFactory(ToolCallingManager.builder().build(),
                    new RetryTemplate(), ObservationRegistry.NOOP, cache, providerProperties,
                    platformProviderConfigService);
        }

        /** 本地桩：**捕获任何路径**（根 context），供断言「实际请求路径」。 */
        private HttpServer startStub(AtomicReference<String> capturedPath) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                capturedPath.set(exchange.getRequestURI().getPath());
                byte[] body = OK_JSON.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            return server;
        }

        @Test
        @DisplayName("设 messagesPath ⇒ 实际请求打到该路径（字段被真实消费，而非只写不读）")
        void shouldOverrideActualRequestPath() throws IOException {
            AtomicReference<String> capturedPath = new AtomicReference<>();
            HttpServer server = startStub(capturedPath);
            try {
                String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
                when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn(baseUrl);
                LlmProviderProfile provider = llmProviderWithExtra("ANTHROPIC_COMPATIBLE", baseUrl, "MiniMax-M2.5",
                        Map.of(ExtraConfigPaths.MESSAGES_PATH, "/custom/messages"));

                ChatModel model = realFactory().createChatModel(provider, "sk-test", agent(), "MiniMax-M2.5");
                model.call(new Prompt("ping"));

                assertThat(capturedPath.get()).isEqualTo("/custom/messages");
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("未设 messagesPath ⇒ 仍走 Spring AI 默认 /v1/messages（行为与 M-5 前逐字一致）")
        void shouldKeepDefaultPathWhenNotConfigured() throws IOException {
            AtomicReference<String> capturedPath = new AtomicReference<>();
            HttpServer server = startStub(capturedPath);
            try {
                String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
                when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn(baseUrl);
                LlmProviderProfile provider = llmProvider("ANTHROPIC_COMPATIBLE", baseUrl, "MiniMax-M2.5");

                ChatModel model = realFactory().createChatModel(provider, "sk-test", agent(), "MiniMax-M2.5");
                model.call(new Prompt("ping"));

                assertThat(capturedPath.get()).isEqualTo("/v1/messages");
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("端点路径覆盖并入缓存 key ⇒ 改覆盖项不命中旧实例")
        void shouldIsolateCacheByEndpointPath() {
            LlmProviderProfile provider = llmProviderWithExtra("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic",
                    "MiniMax-M2.5", Map.of(ExtraConfigPaths.MESSAGES_PATH, "/custom/messages"));
            LlmProviderProfile plain = llmProvider("ANTHROPIC_COMPATIBLE", "https://api.minimaxi.com/anthropic", "MiniMax-M2.5");
            when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://api.minimaxi.com/anthropic");

            factory.createChatModel(provider, "sk-test", agent(), "MiniMax-M2.5");
            factory.createChatModel(plain, "sk-test", agent(), "MiniMax-M2.5");

            assertThat(cache.size()).isEqualTo(2);
        }
    }
}
