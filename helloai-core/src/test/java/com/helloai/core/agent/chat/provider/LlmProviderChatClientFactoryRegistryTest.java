package com.helloai.core.agent.chat.provider;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.LlmProviderQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LlmProviderChatClientFactoryRegistry} 单元测试（C1 Provider 生态补全）。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>provider 未找到/未启用抛 BizException；</li>
 *     <li>deepseek 专用 Factory 优先路由（不走协议工厂）；</li>
 *     <li>OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE 按协议类型分发到对应工厂；</li>
 *     <li>协议类型大小写不敏感（与目录可用性判定一致，§C1 修复）；</li>
 *     <li>未知协议类型抛 BizException。</li>
 * </ul>
 */
@DisplayName("LlmProviderChatClientFactoryRegistry")
class LlmProviderChatClientFactoryRegistryTest {

    private LlmProviderChatClientFactoryRegistry registry;
    private LlmProviderQueryService queryService;
    private DeepSeekProviderChatClientFactory deepSeekFactory;
    private OpenAiCompatibleProtocolFactory openAiFactory;
    private AnthropicCompatibleProtocolFactory anthropicFactory;

    @BeforeEach
    void setUp() {
        queryService = mock(LlmProviderQueryService.class);
        deepSeekFactory = mock(DeepSeekProviderChatClientFactory.class);
        openAiFactory = mock(OpenAiCompatibleProtocolFactory.class);
        anthropicFactory = mock(AnthropicCompatibleProtocolFactory.class);
        // protocolFactoryMap() 以 protocolType() 为 key 聚合，mock 默认返回 null 会撞 Duplicate key
        when(openAiFactory.protocolType()).thenReturn("OPENAI_COMPATIBLE");
        when(anthropicFactory.protocolType()).thenReturn("ANTHROPIC_COMPATIBLE");
        registry = new LlmProviderChatClientFactoryRegistry(
                queryService, deepSeekFactory, openAiFactory, anthropicFactory);
    }

    private LlmProvider llmProvider(String providerCode, String protocolType) {
        LlmProvider provider = new LlmProvider();
        provider.setProviderCode(providerCode);
        provider.setProtocolType(protocolType);
        return provider;
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(1L);
        return agent;
    }

    @Nested
    @DisplayName("Provider 解析")
    class ProviderResolution {

        @Test
        @DisplayName("provider 未找到抛 BizException")
        void shouldThrowWhenProviderNotFound() {
            when(queryService.findByCode("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> registry.createChatClient("unknown", "sk-test", agent(), null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("Provider 未找到或未启用: unknown");
            verify(deepSeekFactory, never()).createChatClient(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("未知协议类型抛 BizException")
        void shouldThrowWhenProtocolTypeUnsupported() {
            LlmProvider provider = llmProvider("gemini", "GEMINI_NATIVE");
            when(queryService.findByCode("gemini")).thenReturn(Optional.of(provider));

            assertThatThrownBy(() -> registry.createChatClient("gemini", "sk-test", agent(), null))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不支持的 protocol_type: GEMINI_NATIVE");
        }
    }

    @Nested
    @DisplayName("路由分发")
    class Routing {

        @Test
        @DisplayName("deepseek 走专用 Factory，不经过协议工厂")
        void shouldRouteDeepSeekToDedicatedFactory() {
            LlmProvider provider = llmProvider("deepseek", "OPENAI_COMPATIBLE");
            when(queryService.findByCode("deepseek")).thenReturn(Optional.of(provider));
            when(deepSeekFactory.supports("deepseek")).thenReturn(true);
            ChatClient expected = mock(ChatClient.class);
            when(deepSeekFactory.createChatClient("sk-test", agent(), null)).thenReturn(expected);

            ChatClient result = registry.createChatClient("deepseek", "sk-test", agent(), null);

            assertThat(result).isSameAs(expected);
            verify(deepSeekFactory).createChatClient("sk-test", agent(), null);
            verify(openAiFactory, never()).createChatClient(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("OPENAI_COMPATIBLE 路由到 OpenAI 兼容工厂")
        void shouldRouteOpenAiCompatible() {
            LlmProvider provider = llmProvider("moonshot", "OPENAI_COMPATIBLE");
            when(queryService.findByCode("moonshot")).thenReturn(Optional.of(provider));
            ChatClient expected = mock(ChatClient.class);
            when(openAiFactory.createChatClient(provider, "sk-test", agent(), "moonshot-v1-8k"))
                    .thenReturn(expected);

            ChatClient result = registry.createChatClient("moonshot", "sk-test", agent(), "moonshot-v1-8k");

            assertThat(result).isSameAs(expected);
            verify(openAiFactory).createChatClient(provider, "sk-test", agent(), "moonshot-v1-8k");
            verify(anthropicFactory, never()).createChatClient(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("ANTHROPIC_COMPATIBLE 路由到 Anthropic 兼容工厂")
        void shouldRouteAnthropicCompatible() {
            LlmProvider provider = llmProvider("minimax", "ANTHROPIC_COMPATIBLE");
            when(queryService.findByCode("minimax")).thenReturn(Optional.of(provider));
            ChatClient expected = mock(ChatClient.class);
            when(anthropicFactory.createChatClient(provider, "sk-test", agent(), null))
                    .thenReturn(expected);

            ChatClient result = registry.createChatClient("minimax", "sk-test", agent(), null);

            assertThat(result).isSameAs(expected);
            verify(anthropicFactory).createChatClient(provider, "sk-test", agent(), null);
        }

        @Test
        @DisplayName("协议类型小写时仍可路由（大小写归一）")
        void shouldRouteLowercaseProtocolType() {
            LlmProvider provider = llmProvider("moonshot", "openai_compatible");
            when(queryService.findByCode("moonshot")).thenReturn(Optional.of(provider));
            ChatClient expected = mock(ChatClient.class);
            when(openAiFactory.createChatClient(provider, "sk-test", agent(), null))
                    .thenReturn(expected);

            ChatClient result = registry.createChatClient("moonshot", "sk-test", agent(), null);

            assertThat(result).isSameAs(expected);
            verify(openAiFactory).createChatClient(provider, "sk-test", agent(), null);
        }
    }
}
