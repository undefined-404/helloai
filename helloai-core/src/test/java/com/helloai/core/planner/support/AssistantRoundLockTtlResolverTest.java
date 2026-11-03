package com.helloai.core.planner.support;

import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.config.AssistantChatProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AssistantRoundLockTtlResolver} 单测（N-1）：锁 TTL 不得短于 provider 读超时。
 */
class AssistantRoundLockTtlResolverTest {

    @Test
    void raisesToProviderReadTimeout_plusMargin() {
        // 配置下限 240 < 读超时 600s ⇒ 抬到 601s
        AssistantRoundLockTtlResolver resolver = resolver(240, 600000);
        resolver.init();
        assertEquals(601, resolver.effectiveTtlSeconds());
    }

    @Test
    void keepsLargerConfiguredValue() {
        AssistantRoundLockTtlResolver resolver = resolver(2000, 600000);
        resolver.init();
        assertEquals(2000, resolver.effectiveTtlSeconds());
    }

    @Test
    void zeroReadTimeout_keepsConfiguredLowerBound() {
        AssistantRoundLockTtlResolver resolver = resolver(240, 0);
        resolver.init();
        assertEquals(240, resolver.effectiveTtlSeconds());
    }

    @Test
    void nonPositiveConfigured_failsFast() {
        AssistantRoundLockTtlResolver resolver = resolver(0, 600000);
        assertThrows(IllegalStateException.class, resolver::init);
    }

    private static AssistantRoundLockTtlResolver resolver(long configured, int readTimeoutMs) {
        AssistantChatProperties chat = new AssistantChatProperties();
        chat.setRoundLockTtlSeconds(configured);
        AgentProviderProperties provider = new AgentProviderProperties();
        AgentProviderProperties.ProviderConfig config = new AgentProviderProperties.ProviderConfig();
        config.setReadTimeoutMs(readTimeoutMs);
        provider.getProviders().put("deepseek", config);
        return new AssistantRoundLockTtlResolver(chat, provider);
    }
}