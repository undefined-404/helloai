package com.helloai.core.system.quota.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BalanceCredentialResolverRegistry} 密钥来源路由单测（G-026 Q-9）。
 *
 * <p>锁定「deepseek / moonshot / minimax → vault；bocha → sys_config」的路由口径（设计 §2.4），
 * 未知 provider 返回空。</p>
 */
@DisplayName("G-026 凭证来源路由")
class BalanceCredentialResolverRegistryTest {

    @Test
    @DisplayName("deepseek/moonshot/minimax 走 vault，bocha 走 sys_config")
    void routesVaultVsSysConfig() {
        PlatformApiKeyResolver platformKey = mock(PlatformApiKeyResolver.class);
        WebSearchApiKeyResolver webSearch = mock(WebSearchApiKeyResolver.class);
        when(platformKey.resolve("deepseek")).thenReturn(Optional.of("vault-ds"));
        when(platformKey.resolve("moonshot")).thenReturn(Optional.of("vault-ms"));
        when(platformKey.resolve("minimax")).thenReturn(Optional.of("vault-mm"));
        when(webSearch.resolve("bocha")).thenReturn(Optional.of("sys-bocha"));

        BalanceCredentialResolverRegistry registry = new BalanceCredentialResolverRegistry(platformKey, webSearch);

        assertThat(registry.resolve("deepseek")).contains("vault-ds");
        assertThat(registry.resolve("moonshot")).contains("vault-ms");
        assertThat(registry.resolve("minimax")).contains("vault-mm");
        assertThat(registry.resolve("bocha")).contains("sys-bocha");
    }

    @Test
    @DisplayName("未知 provider（如通义 tongyi）无对应 resolver → 空（不进列表、不报错）")
    void unknownProviderIsEmpty() {
        BalanceCredentialResolverRegistry registry = new BalanceCredentialResolverRegistry(
                mock(PlatformApiKeyResolver.class), mock(WebSearchApiKeyResolver.class));

        assertThat(registry.resolve("tongyi")).isEmpty();
        assertThat(registry.resolve("unknown")).isEmpty();
    }

    @Test
    @DisplayName("resolver 返回空白串视同无凭据（由 resolver 层归一，注册表透传 Optional.empty）")
    void propagatesEmpty() {
        PlatformApiKeyResolver platformKey = mock(PlatformApiKeyResolver.class);
        WebSearchApiKeyResolver webSearch = mock(WebSearchApiKeyResolver.class);
        when(platformKey.resolve("deepseek")).thenReturn(Optional.empty());

        BalanceCredentialResolverRegistry registry = new BalanceCredentialResolverRegistry(platformKey, webSearch);

        assertThat(registry.resolve("deepseek")).isEmpty();
    }
}
