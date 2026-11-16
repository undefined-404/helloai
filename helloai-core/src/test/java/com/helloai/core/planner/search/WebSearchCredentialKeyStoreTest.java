package com.helloai.core.planner.search;

import com.helloai.common.config.WebSearchProperties;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.system.service.SysConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link WebSearchCredentialKeyStore#maskedBochaApiKey()} 口径单测。
 *
 * <p>设置页「联网搜索」区块的回显契约：与 LLM Provider 平台 Key 同形的
 * 「前 4 + … + 后 4」，未配置返回 {@code null}（前端渲染「未配置」）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("博查 API Key 脱敏回显")
class WebSearchCredentialKeyStoreTest {

    @Mock
    private SysConfigService sysConfigService;

    @Mock
    private CredentialCryptoService credentialCryptoService;

    @Mock
    private WebSearchProperties properties;

    private WebSearchCredentialKeyStore newStore() {
        return new WebSearchCredentialKeyStore(sysConfigService, credentialCryptoService, properties);
    }

    @Test
    @DisplayName("sys_config 有值 → 前 4 + … + 后 4")
    void masked_keepsBothEdgesForStoredKey() {
        when(sysConfigService.getValue(WebSearchCredentialKeyStore.BOCHA_API_KEY_CONFIG_KEY))
                .thenReturn("enc:CIPHERTEXT");
        when(credentialCryptoService.decryptFromBase64("CIPHERTEXT"))
                .thenReturn("sk-bocha-0123456789de56");

        assertThat(newStore().maskedBochaApiKey()).isEqualTo("sk-b…de56");
    }

    @Test
    @DisplayName("sys_config 未配置且 yml 兜底为占位字面量 → null（不渲染点号串）")
    void masked_returnsNullWhenNotConfigured() {
        when(sysConfigService.getValue(WebSearchCredentialKeyStore.BOCHA_API_KEY_CONFIG_KEY))
                .thenReturn("");
        when(properties.getBochaApiKey()).thenReturn("${BOCHA_API_KEY:}");

        assertThat(newStore().maskedBochaApiKey()).isNull();
    }

    @Test
    @DisplayName("历史明文（无 enc: 前缀）也按同口径脱敏")
    void masked_handlesLegacyPlaintext() {
        when(sysConfigService.getValue(WebSearchCredentialKeyStore.BOCHA_API_KEY_CONFIG_KEY))
                .thenReturn("sk-legacy-abcdefgh1234");

        assertThat(newStore().maskedBochaApiKey()).isEqualTo("sk-l…1234");
    }
}
