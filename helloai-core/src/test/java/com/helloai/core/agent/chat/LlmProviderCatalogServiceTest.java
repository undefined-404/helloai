package com.helloai.core.agent.chat;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.chat.provider.LlmProviderChatClientFactoryRegistry;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.LlmProviderCatalogService;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.agent.service.impl.LlmProviderCatalogServiceImpl;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.LlmProviderQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LlmProviderCatalogService} 单元测试（C1 Provider 生态补全）。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>listProviders：factorySupported 判定（deepseek 特判 / 协议类型 / 未知协议）；</li>
 *     <li>available = enabled && apiKeyConfigured && factorySupported 组合；</li>
 *     <li>isProviderAvailable：null/blank 拒绝、大小写不敏感匹配；</li>
 *     <li>provisionPlatformCredential：modelType 前缀解析 + execution.provider 兜底
 *         + 不可用静默跳过（内部 LLM Agent 不再持有 AGENT 级密钥快照）。</li>
 * </ul>
 */
@DisplayName("LlmProviderCatalogService")
class LlmProviderCatalogServiceTest {

    private LlmProviderQueryService queryService;
    private AgentExecutionProperties executionProperties;
    private LlmProviderChatClientFactoryRegistry factoryRegistry;
    private PlatformProviderConfigService platformProviderConfigService;
    private LlmProviderCatalogService catalogService;

    @BeforeEach
    void setUp() {
        queryService = mock(LlmProviderQueryService.class);
        executionProperties = mock(AgentExecutionProperties.class);
        factoryRegistry = mock(LlmProviderChatClientFactoryRegistry.class);
        platformProviderConfigService = mock(PlatformProviderConfigService.class);
        catalogService = new LlmProviderCatalogServiceImpl(queryService, executionProperties,
                factoryRegistry, platformProviderConfigService);
    }

    private LlmProvider llmProvider(String providerCode, String providerName,
                                    String protocolType, Integer enabled) {
        LlmProvider provider = new LlmProvider();
        provider.setProviderCode(providerCode);
        provider.setProviderName(providerName);
        provider.setProtocolType(protocolType);
        provider.setEnabled(enabled);
        return provider;
    }

    private Agent agent(String modelType) {
        Agent agent = new Agent();
        agent.setId(1L);
        agent.setModelType(modelType);
        return agent;
    }

    @Nested
    @DisplayName("listProviders 工厂支持性")
    class FactorySupported {

        @Test
        @DisplayName("deepseek 走专用 Factory 特判，协议类型任意非空均支持")
        void shouldMarkDeepSeekSupported() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("deepseek", "DeepSeek", "OPENAI_COMPATIBLE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("deepseek")).thenReturn(true);

            List<LlmProviderCatalogService.ProviderCatalogItem> items = catalogService.listProviders();

            assertThat(items).hasSize(1);
            assertThat(items.get(0).factorySupported()).isTrue();
            assertThat(items.get(0).available()).isTrue();
        }

        @Test
        @DisplayName("OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE 协议被支持")
        void shouldSupportKnownProtocolTypes() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("moonshot", "Moonshot", "OPENAI_COMPATIBLE", 1),
                    llmProvider("minimax", "MiniMax", "ANTHROPIC_COMPATIBLE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("moonshot")).thenReturn(true);
            when(platformProviderConfigService.isApiKeyConfigured("minimax")).thenReturn(true);

            List<LlmProviderCatalogService.ProviderCatalogItem> items = catalogService.listProviders();

            assertThat(items).hasSize(2);
            assertThat(items).allSatisfy(item -> {
                assertThat(item.factorySupported()).isTrue();
                assertThat(item.available()).isTrue();
            });
        }

        @Test
        @DisplayName("未知协议 GEMINI_NATIVE 不支持，available=false")
        void shouldRejectUnknownProtocolType() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("gemini", "Gemini", "GEMINI_NATIVE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("gemini")).thenReturn(true);

            List<LlmProviderCatalogService.ProviderCatalogItem> items = catalogService.listProviders();

            assertThat(items.get(0).factorySupported()).isFalse();
            assertThat(items.get(0).available()).isFalse();
        }

        @Test
        @DisplayName("protocolType 为 null 时不支持（deepseek 特判之前拦截）")
        void shouldRejectNullProtocolType() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("moonshot", "Moonshot", null, 1)));
            when(platformProviderConfigService.isApiKeyConfigured("moonshot")).thenReturn(true);

            List<LlmProviderCatalogService.ProviderCatalogItem> items = catalogService.listProviders();

            assertThat(items.get(0).factorySupported()).isFalse();
        }

        @Test
        @DisplayName("providerCode 归一为小写")
        void shouldNormalizeProviderCodeToLowerCase() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("Moonshot", "Moonshot", "OPENAI_COMPATIBLE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("moonshot")).thenReturn(true);

            List<LlmProviderCatalogService.ProviderCatalogItem> items = catalogService.listProviders();

            assertThat(items.get(0).provider()).isEqualTo("moonshot");
        }
    }

    @Nested
    @DisplayName("listProviders available 组合")
    class AvailableCombination {

        @Test
        @DisplayName("enabled=0 时 available=false，即使其他条件满足")
        void shouldNotAvailableWhenDisabled() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("moonshot", "Moonshot", "OPENAI_COMPATIBLE", 0)));
            when(platformProviderConfigService.isApiKeyConfigured("moonshot")).thenReturn(true);

            List<LlmProviderCatalogService.ProviderCatalogItem> items = catalogService.listProviders();

            assertThat(items.get(0).apiKeyConfigured()).isTrue();
            assertThat(items.get(0).factorySupported()).isTrue();
            assertThat(items.get(0).available()).isFalse();
        }

        @Test
        @DisplayName("平台 Key 未配置时 available=false，factorySupported 仍为 true")
        void shouldNotAvailableWithoutApiKey() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("moonshot", "Moonshot", "OPENAI_COMPATIBLE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("moonshot")).thenReturn(false);

            List<LlmProviderCatalogService.ProviderCatalogItem> items = catalogService.listProviders();

            assertThat(items.get(0).apiKeyConfigured()).isFalse();
            assertThat(items.get(0).factorySupported()).isTrue();
            assertThat(items.get(0).available()).isFalse();
        }
    }

    @Nested
    @DisplayName("isProviderAvailable")
    class ProviderAvailability {

        @Test
        @DisplayName("null / 空白参数返回 false")
        void shouldRejectNullOrBlank() {
            assertThat(catalogService.isProviderAvailable(null)).isFalse();
            assertThat(catalogService.isProviderAvailable("  ")).isFalse();
        }

        @Test
        @DisplayName("大小写不敏感匹配可用项")
        void shouldMatchCaseInsensitive() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("moonshot", "Moonshot", "OPENAI_COMPATIBLE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("moonshot")).thenReturn(true);

            assertThat(catalogService.isProviderAvailable("MOONSHOT")).isTrue();
        }

        @Test
        @DisplayName("不可用项返回 false")
        void shouldReturnFalseWhenNotAvailable() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("moonshot", "Moonshot", "OPENAI_COMPATIBLE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("moonshot")).thenReturn(false);

            assertThat(catalogService.isProviderAvailable("moonshot")).isFalse();
        }
    }

    @Nested
    @DisplayName("provisionPlatformCredential 平台凭证核对")
    class ProvisionPlatformCredential {

        @Test
        @DisplayName("modelType 为空时回退 execution.provider，核对通过返回 true")
        void shouldFallbackToExecutionProvider() {
            when(executionProperties.getProvider()).thenReturn("deepseek");
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("deepseek", "DeepSeek", "OPENAI_COMPATIBLE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("deepseek")).thenReturn(true);

            boolean bound = catalogService.provisionPlatformCredential(agent(null));

            // 语义：仅核对平台级凭证，不再向 credential_vault 写入 AGENT 级密钥快照
            assertThat(bound).isTrue();
        }

        @Test
        @DisplayName("modelType 带前缀时按前缀解析 provider，核对通过返回 true")
        void shouldResolveProviderFromModelTypePrefix() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("moonshot", "Moonshot", "OPENAI_COMPATIBLE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("moonshot")).thenReturn(true);

            boolean bound = catalogService.provisionPlatformCredential(
                    agent("moonshot:moonshot-v1-8k"));

            assertThat(bound).isTrue();
        }

        @Test
        @DisplayName("provider 未生效时静默跳过返回 false，不抛错")
        void shouldSkipSilentlyWhenProviderUnavailable() {
            when(executionProperties.getProvider()).thenReturn("deepseek");
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("deepseek", "DeepSeek", "OPENAI_COMPATIBLE", 1)));
            when(platformProviderConfigService.isApiKeyConfigured("deepseek")).thenReturn(false);

            boolean bound = catalogService.provisionPlatformCredential(agent(null));

            assertThat(bound).isFalse();
        }

        @Test
        @DisplayName("平台 Key 已配置但 provider 未启用时同样跳过返回 false")
        void shouldSkipWhenProviderDisabled() {
            when(queryService.listAll()).thenReturn(List.of(
                    llmProvider("moonshot", "Moonshot", "OPENAI_COMPATIBLE", 0)));
            when(platformProviderConfigService.isApiKeyConfigured("moonshot")).thenReturn(true);

            boolean bound = catalogService.provisionPlatformCredential(
                    agent("moonshot:moonshot-v1-8k"));

            assertThat(bound).isFalse();
        }
    }
}
