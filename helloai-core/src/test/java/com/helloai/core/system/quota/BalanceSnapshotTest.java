package com.helloai.core.system.quota;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BalanceSnapshot} 三态工厂契约单测（G-026 Q-9）。
 *
 * <p>锁定「失败不伪报 0」的核心契约：{@code failed} 的 available=false 且数值字段全 null，
 * 前端据此显示「—」；金额型与额度型两栏严格分开。</p>
 */
@DisplayName("G-026 BalanceSnapshot 契约")
class BalanceSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

    @Test
    @DisplayName("live 金额型 → available=true，quotaPercent 为空")
    void liveAmount() {
        BalanceSnapshot s = BalanceSnapshot.live("deepseek", new BigDecimal("42.63"), "CNY", null, null, NOW);

        assertThat(s.available()).isTrue();
        assertThat(s.amount()).isEqualByComparingTo("42.63");
        assertThat(s.currency()).isEqualTo("CNY");
        assertThat(s.quotaPercent()).isNull();
        assertThat(s.source()).isEqualTo(BalanceSnapshot.Source.LIVE);
        assertThat(s.error()).isNull();
    }

    @Test
    @DisplayName("live 额度型 → amount 为空，quotaPercent 有值（不塞进 amount）")
    void liveQuota() {
        BalanceSnapshot s = BalanceSnapshot.live("minimax", null, null, new BigDecimal("55"), "百分比", NOW);

        assertThat(s.available()).isTrue();
        assertThat(s.amount()).isNull();
        assertThat(s.quotaPercent()).isEqualByComparingTo("55");
        assertThat(s.rawUnit()).isEqualTo("百分比");
    }

    @Test
    @DisplayName("failed → available=false 且数值全 null（前端显示「—」，绝不显示 0）")
    void failedHasNoNumbers() {
        BalanceSnapshot s = BalanceSnapshot.failed("bocha", "请求失败", NOW);

        assertThat(s.available()).isFalse();
        assertThat(s.amount()).isNull();
        assertThat(s.quotaPercent()).isNull();
        assertThat(s.currency()).isNull();
        assertThat(s.error()).isEqualTo("请求失败");
        assertThat(s.source()).isEqualTo(BalanceSnapshot.Source.FAILED);
    }

    @Test
    @DisplayName("cached 保留原数值，只改 source 与 fetchedAt")
    void cachedKeepsValues() {
        BalanceSnapshot origin = BalanceSnapshot.live("moonshot", new BigDecimal("14.94"), "CNY", null, null, NOW)
                .withRaw(java.util.Map.of("data", "kept"));
        Instant later = NOW.plusSeconds(30);

        BalanceSnapshot s = BalanceSnapshot.cached(origin, later);

        assertThat(s.source()).isEqualTo(BalanceSnapshot.Source.CACHED);
        assertThat(s.fetchedAt()).isEqualTo(later);
        assertThat(s.amount()).isEqualByComparingTo("14.94");
        assertThat(s.available()).isTrue();
        assertThat(s.rawPayload()).containsKey("data");
    }

    @Test
    @DisplayName("rawPayload 只供落库排障，不进入 API JSON 响应")
    void rawPayloadNotSerialized() throws Exception {
        BalanceSnapshot s = BalanceSnapshot.live("deepseek", new BigDecimal("1"), "CNY", null, null, NOW)
                .withRaw(java.util.Map.of("balance_infos", "raw"));

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        String json = mapper.writeValueAsString(s);

        assertThat(s.rawPayload()).containsKey("balance_infos");
        assertThat(json).doesNotContain("rawPayload").doesNotContain("balance_infos");
    }
}
