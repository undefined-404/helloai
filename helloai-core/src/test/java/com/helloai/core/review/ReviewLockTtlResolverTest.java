package com.helloai.core.review;

import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.config.ReviewProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReviewLockTtlResolver} 单测（`MED-3`，2026-10-15；2026-11-15 补「调用次数」因子）。
 *
 * <p>守的是「核验锁 TTL 不得短于其守护操作的最坏时长」这条<b>跨文件配置序关系</b> ——
 * 两个常量各自看都合理，只有放在一起比大小才暴露不自洽。</p>
 *
 * <p><b>为什么期望值从 {@code 读超时+1} 变成 {@code 读超时×2+1}</b>：不可解析即时重试
 * （{@code helloai.review.unparseable-retry-enabled}，默认开启）让一次核验最多发两次 LLM 调用，
 * 且两次都可能跑到读超时 ⇒ 最坏总时长翻倍。若 TTL 仍按单次推导，就退回 `MED-3` 的
 * 同一类缺陷（锁先过期、L1/L2/L3 抢过期锁并发核验）。关闭重试即回到单次口径，
 * 故 {@code calls} 取自配置而非写死 2 —— 见 {@link #returnsToSingleCallWhenRetryDisabled()}。</p>
 */
@DisplayName("MED-3 核验锁 TTL 解析（配置值作下限，下限随调用次数放大）")
class ReviewLockTtlResolverTest {

    private static AgentProviderProperties providers(int... readTimeoutsMs) {
        AgentProviderProperties props = new AgentProviderProperties();
        Map<String, AgentProviderProperties.ProviderConfig> map = new HashMap<>();
        int i = 0;
        for (int ms : readTimeoutsMs) {
            AgentProviderProperties.ProviderConfig config = new AgentProviderProperties.ProviderConfig();
            config.setReadTimeoutMs(ms);
            map.put("provider-" + i++, config);
        }
        props.setProviders(map);
        return props;
    }

    private static ReviewProperties review(long lockTtlSeconds) {
        ReviewProperties props = new ReviewProperties();
        props.setLockTtlSeconds(lockTtlSeconds);
        return props;
    }

    /** 默认档：不可解析重试开启（产品默认）⇒ 调用次数 = 2。 */
    private static ReviewLockTtlResolver resolver(long configuredTtl, int... readTimeoutsMs) {
        ReviewLockTtlResolver r = new ReviewLockTtlResolver(review(configuredTtl), providers(readTimeoutsMs));
        r.init();
        return r;
    }

    /** 回滚面：关掉重试 ⇒ 调用次数 = 1，TTL 不得被无谓拉长。 */
    private static ReviewLockTtlResolver resolverNoRetry(long configuredTtl, int... readTimeoutsMs) {
        ReviewProperties props = review(configuredTtl);
        props.setUnparseableRetryEnabled(false);
        ReviewLockTtlResolver r = new ReviewLockTtlResolver(props, providers(readTimeoutsMs));
        r.init();
        return r;
    }

    @Test
    @DisplayName("默认档：配置 240s < 180s×2+1 ⇒ 抬到 361s（两次调用都可能跑到读超时）")
    void raisesToDoubleReadTimeoutWhenRetryEnabled() {
        assertThat(resolver(240, 180000).effectiveTtlSeconds()).isEqualTo(361);
    }

    @Test
    @DisplayName("★反证（原缺陷配置）：120s 锁 vs 180s 读超时 × 2 ⇒ 抬到 361s（不再短于调用）")
    void raisesWhenConfiguredBelowReadTimeout() {
        assertThat(resolver(120, 180000).effectiveTtlSeconds()).isEqualTo(361);
    }

    @Test
    @DisplayName("★dev 档：600s 读超时 × 2 + 1 ⇒ 1201s（否则 fresh clone 的 dev 起不来）")
    void raisesForDevProfileSixHundredSeconds() {
        assertThat(resolver(240, 600000).effectiveTtlSeconds()).isEqualTo(1201);
    }

    @Test
    @DisplayName("取各 provider 读超时的最大值（任一 provider 调大即抬高）")
    void usesMaxAcrossProviders() {
        assertThat(resolver(240, 60000, 300000).effectiveTtlSeconds()).isEqualTo(601);
    }

    @Test
    @DisplayName("边界：读超时恰好是整秒（180000ms）⇒ 180×2+1=361，严格大于两次调用时长")
    void strictlyGreaterThanCallDuration() {
        long ttl = resolver(240, 180000).effectiveTtlSeconds();
        assertThat(ttl * 1000L).isGreaterThan(2 * 180000L);
    }

    @Test
    @DisplayName("非整秒读超时向上取整（如 600100ms ⇒ 601s × 2 + 1 = 1203s）")
    void ceilsNonWholeSecondReadTimeout() {
        assertThat(resolver(100, 600100).effectiveTtlSeconds()).isEqualTo(1203);
    }

    @Test
    @DisplayName("★回滚面：关闭不可解析重试 ⇒ 下限回到单次口径（240 配置 + 180s 读超时 ⇒ 240）")
    void returnsToSingleCallWhenRetryDisabled() {
        assertThat(resolverNoRetry(240, 180000).effectiveTtlSeconds()).isEqualTo(240);
        // 配置低于单次下限时仍按单次抬高：120 < 180+1
        assertThat(resolverNoRetry(120, 180000).effectiveTtlSeconds()).isEqualTo(181);
    }

    @Test
    @DisplayName("无 provider 配置（mock/精简装配）⇒ 用配置值，不阻断")
    void fallsBackToConfiguredWithoutProviders() {
        ReviewLockTtlResolver r = new ReviewLockTtlResolver(review(240), new AgentProviderProperties());
        r.init();
        assertThat(r.effectiveTtlSeconds()).isEqualTo(240);
    }

    @Test
    @DisplayName("未走 @PostConstruct 也安全：即时解析而非返回 0（防 tryLock(0,0,…) 语义异常）")
    void safeWithoutInit() {
        ReviewLockTtlResolver r = new ReviewLockTtlResolver(review(240), providers(180000));
        // 不调用 init()：即时解析得 max(240, 180×2+1) = 361（返回 0 会让 tryLock 语义异常）
        assertThat(r.effectiveTtlSeconds()).isEqualTo(361);
    }

    @Test
    @DisplayName("配置值非正 ⇒ 仍 fail-fast（无意义输入，不猜测）")
    void rejectsNonPositiveConfiguredTtl() {
        assertThatThrownBy(() -> resolver(0, 180000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须为正数");
    }

    @Test
    @DisplayName("initialised 场景：合法配置不抛异常")
    void initDoesNotThrowForValidConfig() {
        assertThatCode(() -> resolver(240, 180000)).doesNotThrowAnyException();
    }
}
