package com.helloai.core.agent;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.service.CredentialVaultBindingService;
import com.helloai.core.system.service.CredentialVaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentLlmCredentialResolver} 单元测试（内部 LLM Agent 执行凭证解析）。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>resolveApiKey：平台级优先（实时生效）、Agent 级兜底、双无返回 null；</li>
 *     <li>hasUsableCredential：平台级已配置 / Agent 级启用态凭证即可用、
 *         非 API_KEY_LLM 放行、查询异常防御式降级 false。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLlmCredentialResolver")
class AgentLlmCredentialResolverTest {

    @Mock
    private AgentExecutionProperties executionProperties;

    @Mock
    private PlatformProviderConfigService platformProviderConfigService;

    @Mock
    private CredentialVaultBindingService credentialVaultBindingService;

    @Mock
    private CredentialVaultService credentialVaultService;

    private AgentLlmCredentialResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new AgentLlmCredentialResolver(executionProperties,
                platformProviderConfigService, credentialVaultBindingService, credentialVaultService);
        // 默认兜底 provider（null/非 LLM 用例不触达，故 lenient）
        lenient().when(executionProperties.getProvider()).thenReturn("deepseek");
    }

    private Agent llmAgent(Long id, String modelType) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setModelType(modelType);
        agent.setAccessType(AgentAccessType.API_KEY_LLM);
        return agent;
    }

    private Agent cliAgent(Long id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setAccessType(AgentAccessType.CLI_CLIENT);
        return agent;
    }

    @Nested
    @DisplayName("resolveApiKey 平台级优先")
    class ResolveApiKey {

        @Test
        @DisplayName("agent 为 null 时返回 null")
        void shouldReturnNullForNullAgent() {
            assertThat(resolver.resolveApiKey(null)).isNull();
        }

        @Test
        @DisplayName("平台级已配置时直接返回，不再查 Agent 级凭证")
        void shouldPreferPlatformKey() {
            when(platformProviderConfigService.getApiKey("deepseek")).thenReturn("sk-platform");

            String key = resolver.resolveApiKey(llmAgent(1L, null));

            assertThat(key).isEqualTo("sk-platform");
            verify(credentialVaultBindingService, never())
                    .getAgentApiKeyPlaintext(org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("modelType 带前缀时按前缀解析 provider 查询平台级密钥")
        void shouldResolveProviderFromModelTypePrefix() {
            when(platformProviderConfigService.getApiKey("moonshot")).thenReturn("sk-moonshot");

            String key = resolver.resolveApiKey(llmAgent(1L, "moonshot:moonshot-v1-8k"));

            assertThat(key).isEqualTo("sk-moonshot");
            verify(platformProviderConfigService).getApiKey("moonshot");
        }

        @Test
        @DisplayName("平台级缺失时回退 Agent 级自定义密钥")
        void shouldFallbackToAgentKeyWhenPlatformMissing() {
            when(platformProviderConfigService.getApiKey("deepseek")).thenReturn(null);
            when(credentialVaultBindingService.getAgentApiKeyPlaintext(1L, "deepseek"))
                    .thenReturn("sk-agent");

            String key = resolver.resolveApiKey(llmAgent(1L, null));

            assertThat(key).isEqualTo("sk-agent");
        }

        @Test
        @DisplayName("平台级为空白时同样回退 Agent 级")
        void shouldFallbackToAgentKeyWhenPlatformBlank() {
            when(platformProviderConfigService.getApiKey("deepseek")).thenReturn("  ");
            when(credentialVaultBindingService.getAgentApiKeyPlaintext(1L, "deepseek"))
                    .thenReturn("sk-agent");

            String key = resolver.resolveApiKey(llmAgent(1L, null));

            assertThat(key).isEqualTo("sk-agent");
        }

        @Test
        @DisplayName("平台级与 Agent 级均未配置时返回 null")
        void shouldReturnNullWhenBothMissing() {
            when(platformProviderConfigService.getApiKey("deepseek")).thenReturn(null);
            when(credentialVaultBindingService.getAgentApiKeyPlaintext(1L, "deepseek"))
                    .thenReturn(null);

            assertThat(resolver.resolveApiKey(llmAgent(1L, null))).isNull();
        }
    }

    @Nested
    @DisplayName("hasUsableCredential 可用性判定")
    class HasUsableCredential {

        @Test
        @DisplayName("agent 为 null 时放行")
        void shouldPassForNullAgent() {
            assertThat(resolver.hasUsableCredential(null)).isTrue();
        }

        @Test
        @DisplayName("非 API_KEY_LLM（CLI_CLIENT）不依赖凭证，直接放行")
        void shouldPassForNonLlmAgent() {
            assertThat(resolver.hasUsableCredential(cliAgent(2L))).isTrue();
            verify(credentialVaultService, never()).hasActiveAgentCredential(
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("平台级已配置即视为可用")
        void shouldBeUsableWhenPlatformConfigured() {
            when(platformProviderConfigService.isApiKeyConfigured("deepseek")).thenReturn(true);

            assertThat(resolver.hasUsableCredential(llmAgent(1L, null))).isTrue();
        }

        @Test
        @DisplayName("平台级未配置但 Agent 级存在启用态凭证时可用")
        void shouldBeUsableWhenAgentCredentialActive() {
            when(platformProviderConfigService.isApiKeyConfigured("deepseek")).thenReturn(false);
            when(credentialVaultService.hasActiveAgentCredential(1L)).thenReturn(true);

            assertThat(resolver.hasUsableCredential(llmAgent(1L, null))).isTrue();
        }

        @Test
        @DisplayName("平台级与 Agent 级均不可用时不可选")
        void shouldBeUnusableWhenBothMissing() {
            when(platformProviderConfigService.isApiKeyConfigured("deepseek")).thenReturn(false);
            when(credentialVaultService.hasActiveAgentCredential(1L)).thenReturn(false);

            assertThat(resolver.hasUsableCredential(llmAgent(1L, null))).isFalse();
        }

        @Test
        @DisplayName("凭证查询抛异常时防御式降级为不可用")
        void shouldFailClosedOnQueryError() {
            when(platformProviderConfigService.isApiKeyConfigured("deepseek"))
                    .thenThrow(new RuntimeException("vault down"));

            assertThat(resolver.hasUsableCredential(llmAgent(1L, null))).isFalse();
        }
    }
}