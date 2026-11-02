package com.helloai.core.system.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.QuotaProperties;
import com.helloai.core.system.entity.LlmBalanceSnapshot;
import com.helloai.core.system.mapper.LlmBalanceSnapshotMapper;
import com.helloai.core.system.quota.impl.BalanceCredentialResolverRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BalanceQueryService} 编排单测（G-026 Q-9）。
 *
 * <p>覆盖设计 §3 Q-5 验收判据：缓存命中（fetchedAt 不变、不再打外部）、失败回填不拖垮、
 * 顺序与 supports 跳过、手动刷新单家、快照清理。Redis 用 RBucket mock，熔断用真实默认实例，
 * 全程不联网、确定性。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("G-026 BalanceQueryService 编排")
class BalanceQueryServiceTest {

    @Mock private BalanceProviderRegistry providerRegistry;
    @Mock private BalanceCredentialResolverRegistry credentialResolverRegistry;
    @Mock private LlmBalanceSnapshotMapper snapshotMapper;
    @Mock private RedissonClient redissonClient;
    @Mock private RBucket<String> bucket;

    private final QuotaProperties properties = new QuotaProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private BalanceQueryService service;

    @BeforeEach
    void setUp() {
        // lenient：refresh 未知/未配置等用例不触缓存，桩在此类用例中未被使用
        lenient().when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        service = new BalanceQueryService(
                providerRegistry, credentialResolverRegistry, properties,
                snapshotMapper, redissonClient, CircuitBreakerRegistry.ofDefaults(), objectMapper);
    }

    @AfterEach
    void tearDown() {
        service.shutdownExecutor();
    }

    /** 记账 provider：记录 fetch 调用次数，按配置返回固定快照或抛异常。 */
    private static final class FakeProvider implements BalanceProvider {
        private final String key;
        private final boolean supported;
        private final BalanceSnapshot result;
        private final boolean throwOnFetch;
        int fetchCount;

        FakeProvider(String key, boolean supported, BalanceSnapshot result, boolean throwOnFetch) {
            this.key = key;
            this.supported = supported;
            this.result = result;
            this.throwOnFetch = throwOnFetch;
        }

        @Override public String key() { return key; }
        @Override public boolean supports(BalanceQueryContext ctx) { return supported; }
        @Override public BalanceSnapshot fetch(BalanceQueryContext ctx) {
            fetchCount++;
            if (throwOnFetch) {
                throw new RuntimeException("boom");
            }
            return result;
        }
    }

    private static BalanceSnapshot live(String key, String amount) {
        return BalanceSnapshot.live(key, new BigDecimal(amount), "CNY", null, null, Instant.now());
    }

    @Test
    @DisplayName("supports=false 的 provider 被跳过，不出现在结果里（通义语义）")
    void skipsUnsupported() {
        FakeProvider off = new FakeProvider("tongyi", false, null, false);
        FakeProvider on = new FakeProvider("deepseek", true, live("deepseek", "42.63"), false);
        when(providerRegistry.all()).thenReturn(List.of(off, on));
        when(bucket.get()).thenReturn(null);

        List<BalanceSnapshot> result = service.overview();

        assertThat(result).extracting(BalanceSnapshot::providerKey).containsExactly("deepseek");
        assertThat(off.fetchCount).isZero();
    }

    @Test
    @DisplayName("结果顺序 = 注册顺序（deepseek/moonshot/minimax/bocha）")
    void preservesOrder() {
        when(providerRegistry.all()).thenReturn(List.of(
                new FakeProvider("deepseek", true, live("deepseek", "1"), false),
                new FakeProvider("moonshot", true, live("moonshot", "2"), false),
                new FakeProvider("minimax", true,
                        BalanceSnapshot.live("minimax", null, null, new BigDecimal("50"), "百分比", Instant.now()), false),
                new FakeProvider("bocha", true, live("bocha", "3"), false)));
        when(bucket.get()).thenReturn(null);

        List<BalanceSnapshot> result = service.overview();

        assertThat(result).extracting(BalanceSnapshot::providerKey)
                .containsExactly("deepseek", "moonshot", "minimax", "bocha");
    }

    @Test
    @DisplayName("单家抛异常不拖垮 overview：失败者返回 failed，其余正常")
    void singleFailureDoesNotBreakOthers() {
        FakeProvider bad = new FakeProvider("deepseek", true, null, true);
        FakeProvider ok = new FakeProvider("moonshot", true, live("moonshot", "14.94"), false);
        when(providerRegistry.all()).thenReturn(List.of(bad, ok));
        when(bucket.get()).thenReturn(null);

        assertThatCode(() -> {
            List<BalanceSnapshot> result = service.overview();

            assertThat(result).hasSize(2);
            BalanceSnapshot failed = result.stream().filter(s -> s.providerKey().equals("deepseek")).findFirst().orElseThrow();
            assertThat(failed.available()).isFalse();
            assertThat(failed.source()).isEqualTo(BalanceSnapshot.Source.FAILED);
            assertThat(failed.error()).contains("熔断或超时");

            BalanceSnapshot good = result.stream().filter(s -> s.providerKey().equals("moonshot")).findFirst().orElseThrow();
            assertThat(good.available()).isTrue();
            assertThat(good.amount()).isEqualByComparingTo("14.94");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缓存命中 → 返回 CACHED 且不再实时抓（多实例不重复打外部）")
    void cacheHitSkipsFetch() {
        FakeProvider p = new FakeProvider("deepseek", true, live("deepseek", "99.99"), false);
        when(providerRegistry.all()).thenReturn(List.of(p));
        when(bucket.get()).thenReturn("{\"available\":true,\"amount\":12.5,\"currency\":\"CNY\","
                + "\"rawUnit\":null,\"quotaPercent\":null,"
                + "\"fetchedAt\":\"2026-11-02T00:00:00Z\",\"error\":null}");

        List<BalanceSnapshot> result = service.overview();

        assertThat(result).hasSize(1);
        BalanceSnapshot s = result.get(0);
        assertThat(s.source()).isEqualTo(BalanceSnapshot.Source.CACHED);
        assertThat(s.amount()).isEqualByComparingTo("12.5");
        assertThat(s.fetchedAt()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
        assertThat(p.fetchCount).isZero();
        verify(bucket, never()).set(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("缓存未命中 → 实时抓并回填缓存（成功 TTL=60s）")
    void cacheMissFetchesAndCaches() {
        FakeProvider p = new FakeProvider("bocha", true, live("bocha", "50.50"), false);
        when(providerRegistry.all()).thenReturn(List.of(p));
        when(bucket.get()).thenReturn(null);

        service.overview();

        assertThat(p.fetchCount).isEqualTo(1);
        verify(bucket).set(anyString(), org.mockito.ArgumentMatchers.eq(60L), any());
        verify(snapshotMapper).insert(any(LlmBalanceSnapshot.class));
    }

    @Test
    @DisplayName("cachedOverview：只读落库快照，绝不触网（仅手动刷新模式）")
    void cachedOverviewReadsDbOnly() {
        FakeProvider p = new FakeProvider("deepseek", true, live("deepseek", "99.99"), false);
        when(providerRegistry.all()).thenReturn(List.of(p));
        LlmBalanceSnapshot row = new LlmBalanceSnapshot();
        row.setProviderKey("deepseek");
        row.setAmount(new BigDecimal("12.5"));
        row.setCurrency("CNY");
        row.setFetchedAt(OffsetDateTime.parse("2026-11-02T00:00:00Z"));
        when(snapshotMapper.selectOne(any())).thenReturn(row);

        List<BalanceSnapshot> result = service.cachedOverview();

        assertThat(result).hasSize(1);
        BalanceSnapshot s = result.get(0);
        assertThat(s.available()).isTrue();
        assertThat(s.source()).isEqualTo(BalanceSnapshot.Source.CACHED);
        assertThat(s.amount()).isEqualByComparingTo("12.5");
        assertThat(s.fetchedAt()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
        assertThat(p.fetchCount).isZero();
        verify(bucket, never()).get();
    }

    @Test
    @DisplayName("cachedOverview：无历史行 → failed(尚未采集)，不触发抓取")
    void cachedOverviewWithoutHistory() {
        FakeProvider p = new FakeProvider("moonshot", true, live("moonshot", "1"), false);
        when(providerRegistry.all()).thenReturn(List.of(p));
        when(snapshotMapper.selectOne(any())).thenReturn(null);

        List<BalanceSnapshot> result = service.cachedOverview();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).available()).isFalse();
        assertThat(result.get(0).error()).contains("尚未采集");
        assertThat(p.fetchCount).isZero();
    }

    @Test
    @DisplayName("cachedOverview：历史行是失败态 → FAILED 且不伪报 0")
    void cachedOverviewKeepsFailure() {
        FakeProvider p = new FakeProvider("bocha", true, live("bocha", "1"), false);
        when(providerRegistry.all()).thenReturn(List.of(p));
        LlmBalanceSnapshot row = new LlmBalanceSnapshot();
        row.setProviderKey("bocha");
        row.setFetchedAt(OffsetDateTime.parse("2026-11-02T00:00:00Z"));
        row.setError("请求失败");
        when(snapshotMapper.selectOne(any())).thenReturn(row);

        BalanceSnapshot s = service.cachedOverview().get(0);

        assertThat(s.available()).isFalse();
        assertThat(s.source()).isEqualTo(BalanceSnapshot.Source.FAILED);
        assertThat(s.amount()).isNull();
        assertThat(s.error()).isEqualTo("请求失败");
        assertThat(p.fetchCount).isZero();
    }

    @Test
    @DisplayName("allProvidersCollectedSince：逐家判断，不因单家刚手动刷新而饿死其余家")
    void allProvidersCollectedSinceIsPerProvider() {
        FakeProvider deepseek = new FakeProvider("deepseek", true, live("deepseek", "1"), false);
        FakeProvider moonshot = new FakeProvider("moonshot", true, live("moonshot", "2"), false);
        when(providerRegistry.all()).thenReturn(List.of(deepseek, moonshot));

        LlmBalanceSnapshot fresh = new LlmBalanceSnapshot();
        fresh.setFetchedAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10));
        LlmBalanceSnapshot stale = new LlmBalanceSnapshot();
        stale.setFetchedAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(3600));
        when(snapshotMapper.selectOne(any())).thenReturn(fresh, stale);

        assertThat(service.allProvidersCollectedSince(Instant.now().minusSeconds(60))).isFalse();
        assertThat(deepseek.fetchCount + moonshot.fetchCount).isZero();
    }

    @Test
    @DisplayName("allProvidersCollectedSince：各家都新 → true；无快照 → false")
    void allProvidersCollectedSinceAllFresh() {
        FakeProvider only = new FakeProvider("deepseek", true, live("deepseek", "1"), false);
        when(providerRegistry.all()).thenReturn(List.of(only));

        LlmBalanceSnapshot fresh = new LlmBalanceSnapshot();
        fresh.setFetchedAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10));
        when(snapshotMapper.selectOne(any())).thenReturn(fresh);
        assertThat(service.allProvidersCollectedSince(Instant.now().minusSeconds(60))).isTrue();

        when(snapshotMapper.selectOne(any())).thenReturn(null);
        assertThat(service.allProvidersCollectedSince(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("refresh：未知 provider → failed(未知供应商)")
    void refreshUnknownProvider() {
        when(providerRegistry.get("unknown")).thenReturn(null);

        BalanceSnapshot s = service.refresh("unknown");

        assertThat(s.available()).isFalse();
        assertThat(s.error()).contains("未知供应商");
    }

    @Test
    @DisplayName("refresh：supports=false（未配 key）→ failed(未配置API Key)，不触网")
    void refreshUnsupportedShowsMissingKey() {
        FakeProvider off = new FakeProvider("deepseek", false, null, false);
        when(providerRegistry.get("deepseek")).thenReturn(off);

        BalanceSnapshot s = service.refresh("deepseek");

        assertThat(s.available()).isFalse();
        assertThat(s.error()).contains("未配置API Key");
        assertThat(off.fetchCount).isZero();
    }

    @Test
    @DisplayName("refresh：成功 → 返回 LIVE 并回填缓存")
    void refreshFetchesAndCaches() {
        FakeProvider p = new FakeProvider("deepseek", true, live("deepseek", "42.63"), false);
        when(providerRegistry.get("deepseek")).thenReturn(p);

        BalanceSnapshot s = service.refresh("deepseek");

        assertThat(s.available()).isTrue();
        assertThat(s.source()).isEqualTo(BalanceSnapshot.Source.LIVE);
        assertThat(p.fetchCount).isEqualTo(1);
        verify(bucket).set(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("purgeExpiredSnapshots：返回删除行数；异常时回退 0（不抛出）")
    void purgeReturnsCountAndSwallowsError() {
        when(snapshotMapper.delete(any())).thenReturn(3);
        assertThat(service.purgeExpiredSnapshots()).isEqualTo(3);

        when(snapshotMapper.delete(any())).thenThrow(new RuntimeException("db down"));
        assertThat(service.purgeExpiredSnapshots()).isZero();
    }
}
