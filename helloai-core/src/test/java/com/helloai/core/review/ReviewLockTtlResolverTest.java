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
 * {@link ReviewLockTtlResolver} 单测（`MED-3`，2026-10-15）。
 *
 * <p>守的是「核验锁 TTL 不得短于 provider 读超时」这条<b>跨文件配置序关系</b> ——
 * 两个常量各自看都合理，只有放在一起比大小才暴露不自洽。</p>
 */
@DisplayName("MED-3 核验锁 TTL 解析（配置值作下限）")
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

    private static ReviewLockTtlResolver resolver(long configuredTtl, int... readTimeoutsMs) {
        ReviewLockTtlResolver r = new ReviewLockTtlResolver(review(configuredTtl), providers(readTimeoutsMs));
        r.init();
        return r;
    }

    @Test
    @DisplayName("默认档：配置 240s > 读超时 180s ⇒ 有效值取配置值 240s")
    void keepsConfiguredWhenAlreadyAbove() {
        assertThat(resolver(240, 180000).effectiveTtlSeconds()).isEqualTo(240);
    }

    @Test
    @DisplayName("★反证（原缺陷配置）：120s 锁 vs 180s 读超时 ⇒ 自动抬高到 180+1=181s（不再短于调用）")
    void raisesWhenConfiguredBelowReadTimeout() {
        assertThat(resolver(120, 180000).effectiveTtlSeconds()).isEqualTo(181);
    }

    @Test
    @DisplayName("★dev 档：600s 读超时 ⇒ 即使配置 240s 也抬到 601s（否则 fresh clone 的 dev 起不来）")
    void raisesForDevProfileSixHundredSeconds() {
        assertThat(resolver(240, 600000).effectiveTtlSeconds()).isEqualTo(601);
    }

    @Test
    @DisplayName("取各 provider 读超时的最大值（任一 provider 调大即抬高）")
    void usesMaxAcrossProviders() {
        assertThat(resolver(240, 60000, 300000).effectiveTtlSeconds()).isEqualTo(301);
    }

    @Test
    @DisplayName("边界：读超时恰好是整秒（180000ms）⇒ 180+1=181，严格大于调用时长")
    void strictlyGreaterThanCallDuration() {
        long ttl = resolver(240, 180000).effectiveTtlSeconds();
        assertThat(ttl * 1000L).isGreaterThan(180000L);
    }

    @Test
    @DisplayName("非整秒读超时向上取整（如 600100ms ⇒ 601s + 1 = 602s）")
    void ceilsNonWholeSecondReadTimeout() {
        assertThat(resolver(100, 600100).effectiveTtlSeconds()).isEqualTo(602);
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
        // 不调用 init()
        assertThat(r.effectiveTtlSeconds()).isEqualTo(240);
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
