package com.helloai.core.system.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.QuotaProperties;
import com.helloai.core.system.entity.LlmBalanceSnapshot;
import com.helloai.core.system.mapper.LlmBalanceSnapshotMapper;
import com.helloai.core.system.quota.impl.BalanceCredentialResolverRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 余额查询编排：取缓存 → 并行 fetch 4 家 → 写快照 → 回填缓存。
 *
 * <p><b>并行</b>：4 家用 {@link CompletableFuture} 并行 + 统一超时，overview 总耗时
 * ≈ 最慢一家（≤ fetchTimeout）而非 4 家之和。</p>
 *
 * <p><b>缓存</b>：Redis RBucket。成功 TTL 60s；失败态 TTL 30s（避免失败时反复重打）。
 * 多实例共享 Redis ⇒ 并发请求只打一次外部接口（设计 §8 判据 5）。</p>
 *
 * <p><b>熔断</b>：走 Resilience4j {@code quotaFetch} 实例（Q-5 在 yml 声明），
 * 单家长期挂掉不拖慢 overview（设计 §4.5）。</p>
 *
 * <p><b>失败不回填 0</b>：单家失败返回 {@code failed(reason)}，前端据此显示「—」
 * 而非 0（设计 §2.3 经验 2 / §8 判据 3）。</p>
 */
@Slf4j
@Service
public class BalanceQueryService {

    /** Redis key 前缀按 CODE_STYLE §20.1：helloai:{domain}:{resource}:{id}。 */
    private static final String CACHE_KEY_PREFIX = "helloai:quota:balance:";

    /** 并行抓取线程数下限；provider 多于 4 家时按注册表规模扩展，避免排队拖长 overview。 */
    private static final int MIN_FETCH_POOL_SIZE = 4;

    private final BalanceProviderRegistry providerRegistry;
    private final BalanceCredentialResolverRegistry credentialResolverRegistry;
    private final QuotaProperties properties;
    private final LlmBalanceSnapshotMapper snapshotMapper;
    private final RedissonClient redissonClient;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public BalanceQueryService(
            BalanceProviderRegistry providerRegistry,
            BalanceCredentialResolverRegistry credentialResolverRegistry,
            QuotaProperties properties,
            LlmBalanceSnapshotMapper snapshotMapper,
            RedissonClient redissonClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.credentialResolverRegistry = credentialResolverRegistry;
        this.properties = properties;
        this.snapshotMapper = snapshotMapper;
        this.redissonClient = redissonClient;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.objectMapper = objectMapper;
        int poolSize = Math.max(MIN_FETCH_POOL_SIZE, providerRegistry.all().size());
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "quota-fetch");
            t.setDaemon(true);
            return t;
        });
    }

    /** 容器关闭时停止抓取线程池（非守护线程不回收会阻塞 JVM 退出）。 */
    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    /**
     * 聚合 4 家余额快照。返回顺序 = 注册顺序（deepseek / moonshot / minimax / bocha）。
     *
     * <p>supports=false（无凭据）的 provider 直接跳过，不产生失败行 —— 与设计「通义不出现」
     * 同源语义（无接口/无凭据者从列表消失而非报错）。</p>
     */
    public List<BalanceSnapshot> overview() {
        List<BalanceProvider> supported = new ArrayList<>();
        for (BalanceProvider provider : providerRegistry.all()) {
            BalanceQueryContext ctx = ctx(provider);
            if (provider.supports(ctx)) {
                supported.add(provider);
            }
        }
        // supports=false（无凭据/无接口）者跳过，不产生失败行 —— 与「通义不出现」同源语义
        List<CompletableFuture<BalanceSnapshot>> futures = supported.stream()
                .map(p -> CompletableFuture.supplyAsync(() -> queryOne(p, ctx(p)), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
    }

    private BalanceQueryContext ctx(BalanceProvider provider) {
        return new BalanceQueryContext(provider.key(), (int) properties.getFetchTimeoutMs());
    }

    /** 手动刷新单家（Q-7 端点用）：跳过缓存直接实时抓。 */
    public BalanceSnapshot refresh(String providerKey) {
        BalanceProvider provider = providerRegistry.get(providerKey);
        if (provider == null) {
            return BalanceSnapshot.failed(providerKey, "未知供应商", Instant.now());
        }
        BalanceQueryContext ctx = new BalanceQueryContext(providerKey, (int) properties.getFetchTimeoutMs());
        if (!provider.supports(ctx)) {
            return BalanceSnapshot.failed(providerKey, "未配置API Key", Instant.now());
        }
        BalanceSnapshot snapshot = fetchAndPersist(provider, ctx);
        if (snapshot == null) {
            snapshot = BalanceSnapshot.failed(providerKey, "熔断或超时", Instant.now());
        }
        refreshCache(snapshot);
        return snapshot;
    }

    /**
     * 只读最近一次落库快照，**不触发任何外部请求**（仅手动刷新模式 / 面板首次打开时用）。
     *
     * <p>与 {@link #overview()} 的区别：overview 在缓存未命中时会实时抓；本方法永远只读
     * {@code llm_balance_snapshot}，因此「不自动刷新」设置下打开页面也不会产生外部调用。
     * 没有历史行时返回 failed(尚未采集)，前端按失败态展示而不是伪报 0。</p>
     */
    public List<BalanceSnapshot> cachedOverview() {
        List<BalanceSnapshot> result = new ArrayList<>();
        for (BalanceProvider provider : providerRegistry.all()) {
            if (!provider.supports(ctx(provider))) {
                continue;
            }
            BalanceSnapshot latest = latestSnapshot(provider.key());
            result.add(latest != null ? latest : BalanceSnapshot.failed(provider.key(), "尚未采集", Instant.now()));
        }
        return result;
    }

    /**
     * 所有受支持供应商的最近快照是否都不早于 {@code threshold}（定时采集据此遵守自动刷新间隔）。
     *
     * <p>逐家判断而非只看全局最新一行：手动刷新单家会写入一条最新快照，若按全局判断，
     * 其余家会被误判为「刚采过」而长期得不到采集。</p>
     */
    public boolean allProvidersCollectedSince(Instant threshold) {
        for (BalanceProvider provider : providerRegistry.all()) {
            if (!provider.supports(ctx(provider))) {
                continue;
            }
            LlmBalanceSnapshot latest = selectLatest(provider.key());
            if (latest == null || latest.getFetchedAt() == null
                    || latest.getFetchedAt().toInstant().isBefore(threshold)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 清理超过保留期的历史快照行（Q-6 定时任务用）。
     *
     * <p>只删最老的一批到阈值外。返回删除行数；幂等，可反复调用。</p>
     */
    public int purgeExpiredSnapshots() {
        try {
            OffsetDateTime cutoff = Instant.now()
                    .minus(Duration.ofDays(properties.getSnapshotRetentionDays()))
                    .atOffset(ZoneOffset.UTC);
            return snapshotMapper.delete(new LambdaQueryWrapper<LlmBalanceSnapshot>()
                    .lt(LlmBalanceSnapshot::getFetchedAt, cutoff));
        } catch (Exception e) {
            log.warn("quota purge expired snapshots failed: {}", e.getMessage());
            return 0;
        }
    }

    // ── 内部 ──────────────────────────────────────────────

    /** 把最近一行快照映射回领域对象；失败行（无金额/额度）按 FAILED 展示，不伪报 0。 */
    private BalanceSnapshot latestSnapshot(String providerKey) {
        LlmBalanceSnapshot row = selectLatest(providerKey);
        if (row == null) {
            return null;
        }
        Instant fetchedAt = row.getFetchedAt() == null ? Instant.now() : row.getFetchedAt().toInstant();
        if (row.getAmount() == null && row.getQuotaPercent() == null) {
            String error = row.getError() == null || row.getError().isBlank()
                    ? "最近一次获取失败" : row.getError();
            return BalanceSnapshot.failed(providerKey, error, fetchedAt);
        }
        return new BalanceSnapshot(providerKey, true, row.getAmount(), row.getCurrency(), null,
                row.getQuotaPercent(), fetchedAt, row.getError(), BalanceSnapshot.Source.CACHED);
    }

    private LlmBalanceSnapshot selectLatest(String providerKey) {
        try {
            LambdaQueryWrapper<LlmBalanceSnapshot> wrapper = new LambdaQueryWrapper<LlmBalanceSnapshot>()
                    .orderByDesc(LlmBalanceSnapshot::getFetchedAt)
                    .last("LIMIT 1");
            if (providerKey != null) {
                wrapper.eq(LlmBalanceSnapshot::getProviderKey, providerKey);
            }
            return snapshotMapper.selectOne(wrapper);
        } catch (Exception e) {
            log.warn("quota latest snapshot {} read failed: {}",
                    providerKey == null ? "any" : providerKey, e.getMessage());
            return null;
        }
    }

    private BalanceSnapshot queryOne(BalanceProvider provider, BalanceQueryContext ctx) {
        String key = provider.key();
        // 1) 查缓存
        BalanceSnapshot cached = readCache(key);
        if (cached != null) {
            return cached;
        }
        // 2) 未命中 → 实时抓（并行，单家每次一个线程）
        BalanceSnapshot snapshot = fetchAndPersist(provider, ctx);
        if (snapshot == null) {
            // 熔断打开时 fetch 直接短路，返回失败态（此态不写成功缓存）
            return BalanceSnapshot.failed(key, "熔断或超时", Instant.now());
        }
        // 3) 回填缓存（成功 60s / 失败态 30s）
        refreshCache(snapshot);
        return snapshot;
    }

    /**
     * 实时抓取并落快照表。走 {@code quotaFetch} 熔断保护；返回 null 表示被熔断拦下
     * （此时不落库也不写缓存，视为「本次失败」）。
     */
    private BalanceSnapshot fetchAndPersist(BalanceProvider provider, BalanceQueryContext ctx) {
        try {
            return circuitBreakerRegistry.circuitBreaker("quotaFetch")
                    .executeSupplier(() -> {
                        BalanceSnapshot snapshot = provider.fetch(ctx);
                        persist(snapshot);
                        return snapshot;
                    });
        } catch (Exception e) {
            log.warn("quota fetch {} circuit-open or exec failed: {}", provider.key(), e.getMessage());
            return null;
        }
    }

    private void persist(BalanceSnapshot snapshot) {
        try {
            LlmBalanceSnapshot entity = new LlmBalanceSnapshot();
            entity.setProviderKey(snapshot.providerKey());
            entity.setAmount(snapshot.amount());
            entity.setCurrency(snapshot.currency());
            entity.setQuotaPercent(snapshot.quotaPercent());
            entity.setRawPayload(snapshot.rawPayload());
            entity.setFetchedAt(snapshot.fetchedAt().atOffset(ZoneOffset.UTC));
            entity.setError(snapshot.error());
            snapshotMapper.insert(entity);
        } catch (Exception e) {
            log.warn("quota persist {} failed: {}", snapshot.providerKey(), e.getMessage());
        }
    }

    private void refreshCache(BalanceSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(new CachePayload(
                    snapshot.available(), snapshot.amount(), snapshot.currency(),
                    snapshot.rawUnit(), snapshot.quotaPercent(), snapshot.fetchedAt().toString(),
                    snapshot.error()));
            long ttl = snapshot.available() ? properties.getCacheTtlSeconds() : properties.getFailureCacheTtlSeconds();
            bucket(snapshot.providerKey()).set(json, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("quota cache write {} failed: {}", snapshot.providerKey(), e.getMessage());
        }
    }

    private BalanceSnapshot readCache(String providerKey) {
        try {
            RBucket<String> bucket = bucket(providerKey);
            String json = bucket.get();
            if (json == null) {
                return null;
            }
            CachePayload payload = objectMapper.readValue(json, CachePayload.class);
            if (payload.available() == null) {
                return null;
            }
            // 缓存态：fetchedAt 用缓存写入时刻（=原始抓取时刻），source=CACHED
            return new BalanceSnapshot(
                    providerKey, payload.available(),
                    payload.amount(), payload.currency(), payload.rawUnit(),
                    payload.quotaPercent(),
                    payload.fetchedAt() == null ? Instant.now() : Instant.parse(payload.fetchedAt()),
                    payload.error(), BalanceSnapshot.Source.CACHED);
        } catch (Exception e) {
            // 缓存损坏/Redis 故障 → null，走实时抓，不阻断
            log.warn("quota cache read {} failed: {}", providerKey, e.getMessage());
            return null;
        }
    }

    private RBucket<String> bucket(String providerKey) {
        return redissonClient.getBucket(CACHE_KEY_PREFIX + providerKey);
    }

    /** 缓存序列化载体（独立于 {@link BalanceSnapshot}，避免 Source 枚举耦合缓存格式）。 */
    private record CachePayload(
            Boolean available,
            BigDecimal amount,
            String currency,
            String rawUnit,
            BigDecimal quotaPercent,
            String fetchedAt,
            String error
    ) {
    }
}