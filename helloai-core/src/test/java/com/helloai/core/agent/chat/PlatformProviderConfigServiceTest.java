package com.helloai.core.agent.chat;

import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.crypto.CredentialCryptoService;
import com.helloai.core.agent.chat.provider.ProviderChatModelCache;
import com.helloai.core.system.entity.CredentialVault;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.CredentialVaultService;
import com.helloai.core.system.service.LlmProviderQueryService;
import com.helloai.core.system.service.SysConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * PlatformProviderConfigService 单元测试。
 *
 * <p>覆盖（方案B扩展）：DB 优先 / sys_config / yml 三段兜底、轮换幂等（rotate + 缓存 clear）、
 * 脱敏、可用性判定。</p>
 *
 * <p>优先级：llm_provider.base_url &gt; sys_config &gt; yml &gt; null。</p>
 */
@DisplayName("PlatformProviderConfigService")
class PlatformProviderConfigServiceTest {

    private final CredentialVaultService vaultService = mock(CredentialVaultService.class);
    private final CredentialCryptoService cryptoService = mock(CredentialCryptoService.class);
    private final SysConfigService sysConfigService = mock(SysConfigService.class);
    private final AgentProviderProperties providerProperties = mock(AgentProviderProperties.class);
    private final ProviderChatModelCache cache = mock(ProviderChatModelCache.class);
    private final LlmProviderQueryService queryService = mock(LlmProviderQueryService.class);

    private final PlatformProviderConfigService service = new PlatformProviderConfigService(
            vaultService, cryptoService, sysConfigService, providerProperties, cache, queryService);

    private AgentProviderProperties.ProviderConfig ymlConfig(String apiKey) {
        AgentProviderProperties.ProviderConfig config = new AgentProviderProperties.ProviderConfig();
        config.setApiKey(apiKey);
        when(providerProperties.getConfig("deepseek")).thenReturn(config);
        return config;
    }

    private LlmProvider dbProvider(String baseUrl, String defaultModel) {
        LlmProvider lp = new LlmProvider();
        lp.setProviderCode("deepseek");
        lp.setBaseUrl(baseUrl);
        lp.setDefaultModel(defaultModel);
        return lp;
    }

    @Test
    @DisplayName("getApiKey：vault PLATFORM 级 ACTIVE 凭证优先（DB > yml）")
    void shouldPreferVaultOverYml() {
        CredentialVault vault = new CredentialVault();
        vault.setEncryptedValue("encrypted-abc");
        when(vaultService.getActivePlatformApiKey("deepseek")).thenReturn(vault);
        when(cryptoService.decryptFromBase64("encrypted-abc")).thenReturn("sk-vault-real");
        ymlConfig("sk-yml-fallback");

        String key = service.getApiKey("deepseek");

        assertThat(key).isEqualTo("sk-vault-real");
        verify(cryptoService).decryptFromBase64("encrypted-abc");
    }

    @Test
    @DisplayName("getApiKey：vault 无记录时回退 yml")
    void shouldFallbackToYmlWhenVaultEmpty() {
        when(vaultService.getActivePlatformApiKey("deepseek")).thenReturn(null);
        ymlConfig("sk-yml-fallback");

        assertThat(service.getApiKey("deepseek")).isEqualTo("sk-yml-fallback");
    }

    @Test
    @DisplayName("getApiKey：vault 与 yml 均未配置时返回 null")
    void shouldReturnNullWhenNothingConfigured() {
        when(vaultService.getActivePlatformApiKey("deepseek")).thenReturn(null);
        ymlConfig("   ");

        assertThat(service.getApiKey("deepseek")).isNull();
    }

    @Test
    @DisplayName("getBaseUrl：llm_provider > sys_config > yml > null")
    void shouldResolveBaseUrlByPriority() {
        ymlConfig(null);
        AgentProviderProperties.ProviderConfig config = providerProperties.getConfig("deepseek");
        config.setBaseUrl("https://yml.example.com");

        // 1. DB 优先
        when(queryService.findByCode("deepseek"))
                .thenReturn(Optional.of(dbProvider("https://db.example.com", null)));
        when(sysConfigService.getValue("llm.provider.deepseek.base-url")).thenReturn("https://sys.example.com");
        assertThat(service.getBaseUrl("deepseek")).isEqualTo("https://db.example.com");

        // 2. DB 空 → sys_config
        when(queryService.findByCode("deepseek")).thenReturn(Optional.empty());
        assertThat(service.getBaseUrl("deepseek")).isEqualTo("https://sys.example.com");

        // 3. sys_config 空 → yml
        when(sysConfigService.getValue("llm.provider.deepseek.base-url")).thenReturn("   ");
        assertThat(service.getBaseUrl("deepseek")).isEqualTo("https://yml.example.com");

        // 4. 全部空 → null
        when(sysConfigService.getValue("llm.provider.deepseek.base-url")).thenReturn(null);
        config.setBaseUrl(null);
        assertThat(service.getBaseUrl("deepseek")).isNull();
    }

    @Test
    @DisplayName("getDefaultModel：llm_provider > sys_config > yml > null")
    void shouldResolveDefaultModelByPriority() {
        ymlConfig(null);
        AgentProviderProperties.ProviderConfig config = providerProperties.getConfig("deepseek");
        config.setDefaultModel("deepseek-chat");

        // 1. DB 优先
        when(queryService.findByCode("deepseek"))
                .thenReturn(Optional.of(dbProvider(null, "deepseek-reasoner")));
        when(sysConfigService.getValue("llm.provider.deepseek.default-model")).thenReturn("deepseek-old");
        assertThat(service.getDefaultModel("deepseek")).isEqualTo("deepseek-reasoner");

        // 2. DB 空 → sys_config
        when(queryService.findByCode("deepseek")).thenReturn(Optional.empty());
        assertThat(service.getDefaultModel("deepseek")).isEqualTo("deepseek-old");

        // 3. sys_config 空 → yml
        when(sysConfigService.getValue("llm.provider.deepseek.default-model")).thenReturn(null);
        assertThat(service.getDefaultModel("deepseek")).isEqualTo("deepseek-chat");

        // 4. 全部空 → null
        config.setDefaultModel(null);
        assertThat(service.getDefaultModel("deepseek")).isNull();
    }

    @Test
    @DisplayName("saveApiKey：AES 加密 → vault 轮换（PLATFORM）→ ChatModel 缓存全清")
    void shouldRotateAndClearCache() {
        when(cryptoService.encryptToBase64("sk-new")).thenReturn("enc-new");

        service.saveApiKey("deepseek", "sk-new");

        verify(vaultService).rotatePlatformApiKey(eq("deepseek"), eq("enc-new"), isNull(), anyString());
        verify(cache).clear();
    }

    @Test
    @DisplayName("maskApiKey：仅保留尾 4 位；未配置返回 null")
    void shouldMaskApiKey() {
        CredentialVault vault = new CredentialVault();
        vault.setEncryptedValue("encrypted-abc");
        when(vaultService.getActivePlatformApiKey("deepseek")).thenReturn(vault);
        when(cryptoService.decryptFromBase64("encrypted-abc")).thenReturn("sk-1234abcd");
        ymlConfig(null);

        assertThat(service.maskApiKey("deepseek")).isEqualTo("****abcd");

        when(vaultService.getActivePlatformApiKey("deepseek")).thenReturn(null);
        assertThat(service.maskApiKey("deepseek")).isNull();
    }

    @Test
    @DisplayName("isApiKeyConfigured：vault 有 ACTIVE 凭证即 true，不再查 yml")
    void shouldReportConfiguredFromVault() {
        when(vaultService.hasActivePlatformCredential("deepseek")).thenReturn(true);

        assertThat(service.isApiKeyConfigured("deepseek")).isTrue();
        verify(providerProperties, never()).getConfig(anyString());
    }

    @Test
    @DisplayName("isApiKeyConfigured：vault 无记录时回退 yml 判定")
    void shouldReportConfiguredFromYml() {
        when(vaultService.hasActivePlatformCredential("deepseek")).thenReturn(false);
        ymlConfig("sk-yml");

        assertThat(service.isApiKeyConfigured("deepseek")).isTrue();

        ymlConfig("  ");
        assertThat(service.isApiKeyConfigured("deepseek")).isFalse();
    }

    @Test
    @DisplayName("isApiKeyFromVault：直接透传 vault 判定")
    void shouldForwardVaultFlag() {
        when(vaultService.hasActivePlatformCredential("deepseek")).thenReturn(true);
        assertThat(service.isApiKeyFromVault("deepseek")).isTrue();

        when(vaultService.hasActivePlatformCredential("deepseek")).thenReturn(false);
        assertThat(service.isApiKeyFromVault("deepseek")).isFalse();
    }
}
