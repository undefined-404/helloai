package com.helloai.core.system.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BalanceProviderRegistry} 路由与顺序单测（G-026 Q-9）。
 */
@DisplayName("G-026 BalanceProviderRegistry")
class BalanceProviderRegistryTest {

    private static BalanceProvider provider(String key, boolean supported) {
        return new BalanceProvider() {
            @Override public String key() { return key; }
            @Override public boolean supports(BalanceQueryContext ctx) { return supported; }
            @Override public BalanceSnapshot fetch(BalanceQueryContext ctx) {
                return BalanceSnapshot.failed(key, "n/a", Instant.now());
            }
        };
    }

    @Test
    @DisplayName("按 key 检索命中；未知 key 返回 null")
    void getByKey() {
        BalanceProviderRegistry registry = new BalanceProviderRegistry(
                List.of(provider("deepseek", true), provider("bocha", true)));

        assertThat(registry.get("deepseek").key()).isEqualTo("deepseek");
        assertThat(registry.get("bocha").key()).isEqualTo("bocha");
        assertThat(registry.get("unknown")).isNull();
    }

    @Test
    @DisplayName("all() 保持构造时的插入顺序（overview 展示序）")
    void preservesInsertionOrder() {
        BalanceProviderRegistry registry = new BalanceProviderRegistry(
                List.of(provider("deepseek", true), provider("moonshot", true),
                        provider("minimax", true), provider("bocha", true)));

        assertThat(registry.all()).extracting(BalanceProvider::key)
                .containsExactly("deepseek", "moonshot", "minimax", "bocha");
    }

    @Test
    @DisplayName("同名 key 后注册覆盖前者（Map 语义）")
    void laterOverwritesSameKey() {
        BalanceProviderRegistry registry = new BalanceProviderRegistry(
                List.of(provider("deepseek", true), provider("deepseek", false)));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all().get(0).supports(new BalanceQueryContext("deepseek", 1000))).isFalse();
    }
}
