package com.helloai.core.system.service;

import com.helloai.common.base.BizException;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.system.entity.CredentialVault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CredentialVaultBindingService 读取/绑定路径单测（C2 credential_vault 迁移收口）。
 *
 * <p>锁定 Agent 级凭证读取语义：API_KEY_LLM 执行链只认 vault——无 ACTIVE 凭证返回 null
 * （不回落 agent.api_key 工牌）；secretRef 环境变量优先于 encrypted_value 解密。
 * 过渡期双活仅存在于平台级（vault &gt; yml，见 PlatformProviderConfigServiceTest），
 * Agent 级不做任何兜底，保证「工牌与真实凭证」硬隔离不被打穿。</p>
 */
@ExtendWith(MockitoExtension.class)
class CredentialVaultBindingServiceTest {

    private static final Long AGENT_ID = 1L;
    private static final String PROVIDER = "deepseek";

    @Mock
    private CredentialVaultService credentialVaultService;
    @Mock
    private CredentialCryptoService credentialCryptoService;

    private CredentialVaultBindingService bindingService;

    @BeforeEach
    void setUp() {
        bindingService = new CredentialVaultBindingService(
                credentialVaultService, credentialCryptoService);
    }

    private CredentialVault vault(String secretRef, String encryptedValue) {
        CredentialVault vault = new CredentialVault();
        vault.setSecretRef(secretRef);
        vault.setEncryptedValue(encryptedValue);
        return vault;
    }

    @Nested
    @DisplayName("getAgentApiKeyPlaintext 读取优先级")
    class GetAgentApiKeyPlaintext {

        @Test
        @DisplayName("无 ACTIVE vault 凭证时返回 null，不回落任何兜底来源")
        void shouldReturnNullWhenNoActiveVault() {
            when(credentialVaultService.getActiveAgentApiKey(AGENT_ID, PROVIDER)).thenReturn(null);

            String result = bindingService.getAgentApiKeyPlaintext(AGENT_ID, PROVIDER);

            assertThat(result).isNull();
            verify(credentialCryptoService, never()).decryptFromBase64(any());
        }

        @Test
        @DisplayName("secretRef 优先：环境变量有值时直接返回，不触达解密")
        void shouldPreferSecretRefEnvOverEncryptedValue() {
            // 用系统必然存在的 PATH 环境变量验证 secretRef 优先语义（Mockito 禁止 mock System 静态方法）
            CredentialVault vault = vault("PATH", "cipher-ignored");
            when(credentialVaultService.getActiveAgentApiKey(AGENT_ID, PROVIDER)).thenReturn(vault);

            String result = bindingService.getAgentApiKeyPlaintext(AGENT_ID, PROVIDER);

            assertThat(result).isEqualTo(System.getenv("PATH"));
            verify(credentialCryptoService, never()).decryptFromBase64(any());
        }

        @Test
        @DisplayName("secretRef 指向的环境变量为空时抛 BizException（fail-close，不回退 encrypted_value）")
        void shouldThrowWhenSecretRefEnvBlank() {
            CredentialVault vault = vault("HELLOAI_EMPTY_ENV", "cipher");
            when(credentialVaultService.getActiveAgentApiKey(AGENT_ID, PROVIDER)).thenReturn(vault);

            assertThatThrownBy(() -> bindingService.getAgentApiKeyPlaintext(AGENT_ID, PROVIDER))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("secretRef 指向的环境变量为空");
            verify(credentialCryptoService, never()).decryptFromBase64(any());
        }

        @Test
        @DisplayName("无 secretRef 时走 encrypted_value 解密")
        void shouldDecryptEncryptedValueWhenNoSecretRef() {
            CredentialVault vault = vault(null, "cipher-abc");
            when(credentialVaultService.getActiveAgentApiKey(AGENT_ID, PROVIDER)).thenReturn(vault);
            when(credentialCryptoService.decryptFromBase64("cipher-abc")).thenReturn("sk-decrypted");

            String result = bindingService.getAgentApiKeyPlaintext(AGENT_ID, PROVIDER);

            assertThat(result).isEqualTo("sk-decrypted");
        }

        @Test
        @DisplayName("vault 记录缺少 encrypted_value 与 secret_ref 时抛 BizException")
        void shouldThrowWhenVaultMissingBothValueAndRef() {
            CredentialVault vault = vault(null, null);
            when(credentialVaultService.getActiveAgentApiKey(AGENT_ID, PROVIDER)).thenReturn(vault);

            assertThatThrownBy(() -> bindingService.getAgentApiKeyPlaintext(AGENT_ID, PROVIDER))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("缺少 encrypted_value/secret_ref");
        }
    }

    @Nested
    @DisplayName("bindAgentApiKey 写路径")
    class BindAgentApiKey {

        @Test
        @DisplayName("明文先加密后写入 vault，透传 expiresAt/remark")
        void shouldEncryptThenSave() {
            when(credentialCryptoService.encryptToBase64("sk-plain")).thenReturn("cipher-new");
            OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(30);
            when(credentialVaultService.saveAgentApiKeyCredential(
                    eq(AGENT_ID), eq(PROVIDER), eq("cipher-new"), eq(null), eq(expiresAt), eq("bind-remark")))
                    .thenReturn(vault(null, "cipher-new"));

            bindingService.bindAgentApiKey(AGENT_ID, PROVIDER, "sk-plain", expiresAt, "bind-remark");

            verify(credentialCryptoService).encryptToBase64("sk-plain");
            verify(credentialVaultService).saveAgentApiKeyCredential(
                    AGENT_ID, PROVIDER, "cipher-new", null, expiresAt, "bind-remark");
        }
    }
}
