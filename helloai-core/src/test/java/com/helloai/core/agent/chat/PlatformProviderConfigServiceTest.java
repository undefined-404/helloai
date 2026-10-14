package com.helloai.core.agent.chat;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.constant.CredentialOwnerType;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.system.crypto.CredentialFingerprint;
import com.helloai.core.system.port.CredentialSecret;
import com.helloai.core.system.service.CredentialVaultService;
import com.helloai.core.system.service.LlmProviderQueryService;
import com.helloai.core.system.service.SysConfigService;
import com.helloai.core.agent.dto.ApiKeySaveOutcome;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.agent.service.impl.PlatformProviderConfigServiceImpl;
import com.helloai.core.agent.chat.provider.ProviderChatModelCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        configService = new PlatformProviderConfigServiceImpl(
                credentialVaultService, credentialCryptoService, sysConfigService,
                providerProperties, providerChatModelCache, llmProviderQueryService);
    }

    private CredentialSecret platformVault(String secretRef, String encryptedValue) {
        return new CredentialSecret(encryptedValue, secretRef);
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
            when(credentialVaultService.getActivePlatformApiKeySecret(PROVIDER))
                    .thenReturn(platformVault(null, "cipher-vault"));
            when(credentialCryptoService.decryptFromBase64("cipher-vault")).thenReturn("sk-vault");

            assertThat(configService.getApiKey(PROVIDER)).isEqualTo("sk-vault");
        }

        @Test
        @DisplayName("vault 有 secretRef 时环境变量优先（vault 内 secretRef 高于 encrypted_value）")
        void shouldPreferVaultSecretRefEnv() {
            // 用系统必然存在的 PATH 环境变量验证 secretRef 优先语义（Mockito 禁止 mock System 静态方法）
            when(credentialVaultService.getActivePlatformApiKeySecret(PROVIDER))
                    .thenReturn(platformVault("PATH", "cipher-ignored"));

            assertThat(configService.getApiKey(PROVIDER)).isEqualTo(System.getenv("PATH"));
        }

        @Test
        @DisplayName("vault secretRef 指向的环境变量为空时回退 yml，不抛错")
        void shouldFallbackToYmlWhenVaultSecretRefEnvBlank() {
            when(credentialVaultService.getActivePlatformApiKeySecret(PROVIDER))
                    .thenReturn(platformVault("HELLOAI_EMPTY_ENV", "cipher"));
            when(providerProperties.getConfig(PROVIDER)).thenReturn(ymlConfig("sk-yml"));

            assertThat(configService.getApiKey(PROVIDER)).isEqualTo("sk-yml");
        }

        @Test
        @DisplayName("无 vault 凭证时回退 yml 配置（老环境平滑迁移）")
        void shouldFallbackToYmlWhenNoVault() {
            when(credentialVaultService.getActivePlatformApiKeySecret(PROVIDER)).thenReturn(null);
            when(providerProperties.getConfig(PROVIDER)).thenReturn(ymlConfig("sk-yml"));

            assertThat(configService.getApiKey(PROVIDER)).isEqualTo("sk-yml");
        }

        @Test
        @DisplayName("vault 与 yml 均无配置时返回 null")
        void shouldReturnNullWhenNoVaultAndNoYml() {
            when(credentialVaultService.getActivePlatformApiKeySecret(PROVIDER)).thenReturn(null);
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
            when(credentialVaultService.getActivePlatformApiKeySecret(PROVIDER))
                    .thenReturn(platformVault(null, "cipher-vault"));
            when(credentialCryptoService.decryptFromBase64("cipher-vault")).thenReturn("sk-abc12345");

            assertThat(configService.maskApiKey(PROVIDER)).isEqualTo("****2345");
        }

        @Test
        @DisplayName("maskApiKey：未配置返回 null")
        void shouldReturnNullMaskWhenUnconfigured() {
            when(credentialVaultService.getActivePlatformApiKeySecret(PROVIDER)).thenReturn(null);
            when(providerProperties.getConfig(PROVIDER)).thenReturn(ymlConfig(null));

            assertThat(configService.maskApiKey(PROVIDER)).isNull();
        }
    }

    @Nested
    @DisplayName("saveApiKey 同 provider 内同 Key 去重（M-6）")
    class SaveApiKeyDedup {

        @Test
        @DisplayName("首次录入 ⇒ created=true，落库带明文指纹，并清 ChatModel 缓存")
        void shouldCreateWhenFingerprintAbsent() {
            String fingerprint = CredentialFingerprint.of("sk-new");
            when(credentialVaultService.existsFingerprint(
                    CredentialOwnerType.PLATFORM, 0L, PROVIDER, fingerprint)).thenReturn(false);
            when(credentialCryptoService.encryptToBase64("sk-new")).thenReturn("cipher-new");

            ApiKeySaveOutcome outcome = configService.saveApiKey(PROVIDER, "sk-new");

            assertThat(outcome.created()).isTrue();
            assertThat(outcome.fingerprint()).isEqualTo(fingerprint).hasSize(24);
            verify(credentialVaultService).rotatePlatformApiKey(
                    eq(PROVIDER), eq("cipher-new"), isNull(), anyString(), eq(fingerprint));
            verify(providerChatModelCache).clear();
        }

        @Test
        @DisplayName("同一 Key 二次录入 ⇒ created=false，不落库、不清缓存")
        void shouldNotPersistWhenFingerprintExists() {
            when(credentialVaultService.existsFingerprint(
                    CredentialOwnerType.PLATFORM, 0L, PROVIDER, CredentialFingerprint.of("sk-dup")))
                    .thenReturn(true);

            ApiKeySaveOutcome outcome = configService.saveApiKey(PROVIDER, "sk-dup");

            assertThat(outcome.created()).isFalse();
            assertThat(outcome.message()).isEqualTo(ApiKeySaveOutcome.MSG_ALREADY_EXISTS);
            assertThat(outcome.fingerprint()).isEqualTo(CredentialFingerprint.of("sk-dup"));
            verify(credentialVaultService, never()).rotatePlatformApiKey(
                    any(), any(), any(), any(), any());
            verify(providerChatModelCache, never()).clear();
        }

        @Test
        @DisplayName("判重作用域 = PLATFORM + ownerId 0 + 该 provider（同 Key 跨 provider 不误判）")
        void shouldScopeDedupByOwnerAndProvider() {
            configService.saveApiKey(PROVIDER, "sk-x");

            verify(credentialVaultService).existsFingerprint(
                    CredentialOwnerType.PLATFORM, 0L, PROVIDER, CredentialFingerprint.of("sk-x"));
        }

        @Test
        @DisplayName("历史行指纹回填必须发生在判重之前（否则升级前录入的 Key 判不出来）")
        void shouldBackfillBeforeDedup() {
            configService.saveApiKey(PROVIDER, "sk-order");

            InOrder order = inOrder(credentialVaultService);
            order.verify(credentialVaultService).backfillFingerprints(
                    CredentialOwnerType.PLATFORM, 0L, PROVIDER);
            order.verify(credentialVaultService).existsFingerprint(
                    eq(CredentialOwnerType.PLATFORM), eq(0L), eq(PROVIDER), anyString());
        }

        @Test
        @DisplayName("provider / apiKey 为空仍 fail-close")
        void shouldRejectBlankInputs() {
            assertThatThrownBy(() -> configService.saveApiKey(" ", "sk"))
                    .isInstanceOf(BizException.class).hasMessageContaining("provider");
            assertThatThrownBy(() -> configService.saveApiKey(PROVIDER, " "))
                    .isInstanceOf(BizException.class).hasMessageContaining("apiKey");
        }
    }
}
