package com.helloai.core.agent.chat;

import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.service.CredentialVaultService;
import com.helloai.core.system.service.LlmProviderQueryService;
import com.helloai.core.system.service.SysConfigService;
import com.helloai.core.agent.chat.provider.ProviderChatModelCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PlatformProviderConfigService 读取优先级单测（C2 credential_vault 迁移收口）。
 *
 * <p>锁定平台级「过渡期双活」读取优先级：API Key = vault PLATFORM 级 ACTIVE 凭证
 * （secretRef 环境变量优先、否则解密 encrypted_value）&gt; yml 兜底 &gt; null；
 * vault 记录损坏（secretRef 空环境变量）时回退 yml 而非抛错，保证老环境平滑迁移。</p>
 */
@ExtendWith(MockitoExtension.class)
class PlatformProviderConfigServiceTest {

    private static final String PROVIDER = "deepseek";

    @Mock
    private CredentialVaultService credentialVaultService;
    @Mock
    private CredentialCryptoService credentialCryptoService;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private AgentProviderProperties providerProperties;
    @Mock
    private ProviderChatModelCache providerChatModelCache;
    @Mock
    private LlmProviderQueryService llmProviderQueryService;

    private PlatformProviderConfigService configService;

    @BeforeEach
    void setUp() {
        configService = new PlatformProviderConfigService(
                credentialVaultService, credentialCryptoService, sysConfigService,
                providerProperties, providerChatModelCache, llmProviderQueryService);
    }

    private CredentialVault platformVault(String secretRef, String encryptedValue) {
        CredentialVault vault = new CredentialVault();
        vault.setSecretRef(secretRef);
        vault.setEncryptedValue(encryptedValue);
        return vault;
    }

    private AgentProviderProperties.ProviderConfig ymlConfig(String apiKey) {
        AgentProviderProperties.ProviderConfig config = new AgentProviderProperties.ProviderConfig();
        config.setApiKey(apiKey);
        return config;
    }

    @Nested
    @DisplayName("getApiKey 过渡期双活优先级")
    class GetApiKey {

        @Test
        @DisplayName("vault 有 encrypted_value 时优先解密返回，不读 yml")
        void shouldPreferVaultEncryptedValueOverYml() {
            when(credentialVaultService.getActivePlatformApiKey(PROVIDER))
                    .thenReturn(platformVault(null, "cipher-vault"));
            when(credentialCryptoService.decryptFromBase64("cipher-vault")).thenReturn("sk-vault");

            assertThat(configService.getApiKey(PROVIDER)).isEqualTo("sk-vault");
        }

        @Test
        @DisplayName("vault 有 secretRef 时环境变量优先（vault 内 secretRef 高于 encrypted_value）")
        void shouldPreferVaultSecretRefEnv() {
            // 用系统必然存在的 PATH 环境变量验证 secretRef 优先语义（Mockito 禁止 mock System 静态方法）
            when(credentialVaultService.getActivePlatformApiKey(PROVIDER))
                    .thenReturn(platformVault("PATH", "cipher-ignored"));

            assertThat(configService.getApiKey(PROVIDER)).isEqualTo(System.getenv("PATH"));
        }

        @Test
        @DisplayName("vault secretRef 指向的环境变量为空时回退 yml，不抛错")
        void shouldFallbackToYmlWhenVaultSecretRefEnvBlank() {
            when(credentialVaultService.getActivePlatformApiKey(PROVIDER))
                    .thenReturn(platformVault("HELLOAI_EMPTY_ENV", "cipher"));
            when(providerProperties.getConfig(PROVIDER)).thenReturn(ymlConfig("sk-yml"));

            assertThat(configService.getApiKey(PROVIDER)).isEqualTo("sk-yml");
        }

        @Test
        @DisplayName("无 vault 凭证时回退 yml 配置（老环境平滑迁移）")
        void shouldFallbackToYmlWhenNoVault() {
            when(credentialVaultService.getActivePlatformApiKey(PROVIDER)).thenReturn(null);
            when(providerProperties.getConfig(PROVIDER)).thenReturn(ymlConfig("sk-yml"));

            assertThat(configService.getApiKey(PROVIDER)).isEqualTo("sk-yml");
        }

        @Test
        @DisplayName("vault 与 yml 均无配置时返回 null")
        void shouldReturnNullWhenNoVaultAndNoYml() {
            when(credentialVaultService.getActivePlatformApiKey(PROVIDER)).thenReturn(null);
            when(providerProperties.getConfig(PROVIDER)).thenReturn(ymlConfig(null));

            assertThat(configService.getApiKey(PROVIDER)).isNull();
        }
    }

    @Nested
    @DisplayName("可用性判定与脱敏")
    class Availability {

        @Test
        @DisplayName("isApiKeyConfigured：vault 有 ACTIVE 凭证即 true")
        void shouldBeConfiguredWhenVaultHasCredential() {
            when(credentialVaultService.hasActivePlatformCredential(PROVIDER)).thenReturn(true);

            assertThat(configService.isApiKeyConfigured(PROVIDER)).isTrue();
        }

        @Test
        @DisplayName("isApiKeyConfigured：vault 无、yml 有 key 即 true")
        void shouldBeConfiguredWhenYmlHasKey() {
            when(credentialVaultService.hasActivePlatformCredential(PROVIDER)).thenReturn(false);
            when(providerProperties.getConfig(PROVIDER)).thenReturn(ymlConfig("sk-yml"));

            assertThat(configService.isApiKeyConfigured(PROVIDER)).isTrue();
        }

        @Test
        @DisplayName("isApiKeyConfigured：vault 与 yml 均无时 false")
        void shouldNotBeConfiguredWhenBothMissing() {
            when(credentialVaultService.hasActivePlatformCredential(PROVIDER)).thenReturn(false);
            when(providerProperties.getConfig(PROVIDER)).thenReturn(ymlConfig(null));

            assertThat(configService.isApiKeyConfigured(PROVIDER)).isFalse();
        }

        @Test
        @DisplayName("isApiKeyFromVault：以 vault 是否存在 ACTIVE 凭证为准")
        void shouldMarkFromVaultWhenVaultHasCredential() {
            when(credentialVaultService.hasActivePlatformCredential(PROVIDER)).thenReturn(true);

            assertThat(configService.isApiKeyFromVault(PROVIDER)).isTrue();
        }

        @Test
        @DisplayName("maskApiKey：解密后仅保留尾 4 位")
        void shouldMaskTailFour() {
            when(credentialVaultService.getActivePlatformApiKey(PROVIDER))
                    .thenReturn(platformVault(null, "cipher-vault"));
            when(credentialCryptoService.decryptFromBase64("cipher-vault")).thenReturn("sk-abc12345");

            assertThat(configService.maskApiKey(PROVIDER)).isEqualTo("****2345");
        }

        @Test
        @DisplayName("maskApiKey：未配置返回 null")
        void shouldReturnNullMaskWhenUnconfigured() {
            when(credentialVaultService.getActivePlatformApiKey(PROVIDER)).thenReturn(null);
            when(providerProperties.getConfig(PROVIDER)).thenReturn(ymlConfig(null));

            assertThat(configService.maskApiKey(PROVIDER)).isNull();
        }
    }
}
