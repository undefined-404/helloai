package com.helloai.core.system.quota.impl;

import com.helloai.core.system.quota.BalanceQueryContext;
import com.helloai.core.system.quota.BalanceSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 4 个 provider 的响应解析单测（G-026 Q-9）。
 *
 * <p>冻结设计 §2.1 / §2.2 的真实响应结构与取数口径：金额型写 amount、额度型写
 * quotaPercent（不换算金额）、取不到时返回 {@code failed(reason)} 而非回填 0。
 * 通过子类覆写 {@code doGet} 注入固定响应体，**不触网、确定性纯函数验证**。</p>
 */
@DisplayName("G-026 余额 provider 解析")
class BalanceProviderParsingTest {

    private static final BalanceQueryContext CTX = new BalanceQueryContext("x", 5000);

    /** 所有 key 都解析成功（区分 vault / sys_config 由注册表内部路由，这里两者都通）。 */
    private static BalanceCredentialResolverRegistry resolvable(String value) {
        PlatformApiKeyResolver platformKey = mock(PlatformApiKeyResolver.class);
        WebSearchApiKeyResolver webSearch = mock(WebSearchApiKeyResolver.class);
        when(platformKey.resolve(anyString())).thenReturn(Optional.of(value));
        when(webSearch.resolve(anyString())).thenReturn(Optional.of(value));
        return new BalanceCredentialResolverRegistry(platformKey, webSearch);
    }

    private static BalanceCredentialResolverRegistry missing() {
        PlatformApiKeyResolver platformKey = mock(PlatformApiKeyResolver.class);
        WebSearchApiKeyResolver webSearch = mock(WebSearchApiKeyResolver.class);
        when(platformKey.resolve(anyString())).thenReturn(Optional.empty());
        when(webSearch.resolve(anyString())).thenReturn(Optional.empty());
        return new BalanceCredentialResolverRegistry(platformKey, webSearch);
    }

    /** 覆写 doGet 返回固定响应体（null 表示网络/非2xx 失败）。 */
    static class StubDeepSeek extends DeepSeekBalanceProvider {
        private final String body;
        StubDeepSeek(BalanceCredentialResolverRegistry r, String body) { super(r); this.body = body; }
        @Override protected Optional<String> doGet(BalanceQueryContext ctx, String url, String bearerKey) {
            return Optional.ofNullable(body);
        }
    }

    static class StubMoonshot extends MoonshotBalanceProvider {
        private final String body;
        StubMoonshot(BalanceCredentialResolverRegistry r, String body) { super(r); this.body = body; }
        @Override protected Optional<String> doGet(BalanceQueryContext ctx, String url, String bearerKey) {
            return Optional.ofNullable(body);
        }
    }

    static class StubMiniMax extends MiniMaxBalanceProvider {
        private final String body;
        StubMiniMax(BalanceCredentialResolverRegistry r, String body) { super(r); this.body = body; }
        @Override protected Optional<String> doGet(BalanceQueryContext ctx, String url, String bearerKey) {
            return Optional.ofNullable(body);
        }
    }

    /** 按调用次序返回不同响应，用于验证博查主域失败→回落备域。 */
    static class StubBocha extends BochaBalanceProvider {
        private final List<Optional<String>> responses;
        private final List<String> urls = new ArrayList<>();
        private int idx = 0;
        StubBocha(BalanceCredentialResolverRegistry r, Optional<String>... responses) {
            super(r);
            this.responses = List.of(responses);
        }
        @Override protected Optional<String> doGet(BalanceQueryContext ctx, String url, String bearerKey) {
            urls.add(url);
            return responses.get(Math.min(idx++, responses.size() - 1));
        }
    }

    @Nested
    @DisplayName("DeepSeek（金额型，取 total_balance）")
    class DeepSeek {

        @Test
        @DisplayName("正常响应 → 取 total_balance + CNY，不写 quotaPercent")
        void parsesAmount() {
            String json = "{\"is_available\":true,\"balance_infos\":[{\"currency\":\"CNY\","
                    + "\"total_balance\":\"42.63\",\"granted_balance\":\"0.00\",\"topped_up_balance\":\"42.63\"}]}";
            BalanceSnapshot s = new StubDeepSeek(resolvable("sk"), json).fetch(CTX);

            assertThat(s.available()).isTrue();
            assertThat(s.amount()).isEqualByComparingTo("42.63");
            assertThat(s.currency()).isEqualTo("CNY");
            assertThat(s.quotaPercent()).isNull();
            assertThat(s.source()).isEqualTo(BalanceSnapshot.Source.LIVE);
        }

        @Test
        @DisplayName("缺 balance_infos → failed，不回填 0")
        void failsWhenNoInfos() {
            BalanceSnapshot s = new StubDeepSeek(resolvable("sk"), "{\"is_available\":true}").fetch(CTX);

            assertThat(s.available()).isFalse();
            assertThat(s.amount()).isNull();
            assertThat(s.error()).contains("balance_infos");
        }

        @Test
        @DisplayName("HTTP 失败（doGet 空）→ failed")
        void failsOnTransportError() {
            BalanceSnapshot s = new StubDeepSeek(resolvable("sk"), null).fetch(CTX);
            assertThat(s.available()).isFalse();
            assertThat(s.error()).contains("请求失败");
        }

        @Test
        @DisplayName("无凭据 → failed(未配置API Key)")
        void failsWhenNoKey() {
            BalanceSnapshot s = new StubDeepSeek(missing(), "{}").fetch(CTX);
            assertThat(s.available()).isFalse();
            assertThat(s.error()).contains("未配置API Key");
        }
    }

    @Nested
    @DisplayName("Moonshot（金额型，取 available_balance）")
    class Moonshot {

        @Test
        @DisplayName("正常响应 → 取 available_balance")
        void parsesAmount() {
            String json = "{\"code\":0,\"scode\":\"0\",\"status\":true,\"data\":{"
                    + "\"available_balance\":14.94,\"voucher_balance\":0,\"cash_balance\":14.94,\"currency\":\"CNY\"}}";
            BalanceSnapshot s = new StubMoonshot(resolvable("sk"), json).fetch(CTX);

            assertThat(s.available()).isTrue();
            assertThat(s.amount()).isEqualByComparingTo("14.94");
            assertThat(s.currency()).isEqualTo("CNY");
            assertThat(s.quotaPercent()).isNull();
        }

        @Test
        @DisplayName("空 data → available_balance 缺省 0 → 记为 0 金额（合法零余额）")
        void zeroWhenMissingField() {
            BalanceSnapshot s = new StubMoonshot(resolvable("sk"), "{\"code\":0,\"data\":{}}").fetch(CTX);
            // 字段缺省按 0 解析（该供应商契约 data.available_balance 恒在；缺省即 0 元）
            assertThat(s.available()).isTrue();
            assertThat(s.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("MiniMax（额度型，百分比不换算金额）")
    class MiniMax {

        @Test
        @DisplayName("general 两项百分比 → 取更紧张一档；amount 恒为 null，rawUnit=百分比")
        void takesTighterPercent() {
            String json = "{\"base_resp\":{\"status_code\":0},\"model_remains\":[{"
                    + "\"model_name\":\"general\","
                    + "\"current_interval_status\":1,"
                    + "\"current_interval_remaining_percent\":80,"
                    + "\"current_weekly_status\":1,"
                    + "\"current_weekly_remaining_percent\":55}]}";
            BalanceSnapshot s = new StubMiniMax(resolvable("sk"), json).fetch(CTX);

            assertThat(s.available()).isTrue();
            assertThat(s.amount()).isNull();
            assertThat(s.quotaPercent()).isEqualByComparingTo("55");
            assertThat(s.rawUnit()).isEqualTo("百分比");
        }

        @Test
        @DisplayName("两档 status==3（不限量）→ 100%")
        void unlimitedAsHundred() {
            String json = "{\"model_remains\":[{\"model_name\":\"general\","
                    + "\"current_interval_status\":3,\"current_weekly_status\":3}]}";
            BalanceSnapshot s = new StubMiniMax(resolvable("sk"), json).fetch(CTX);

            assertThat(s.available()).isTrue();
            assertThat(s.quotaPercent()).isEqualByComparingTo("100");
        }

        @Test
        @DisplayName("仅周期档不限量(status=3)、周档有百分比 → 取周档真实百分比")
        void unlimitedIntervalUsesWeekly() {
            String json = "{\"model_remains\":[{\"model_name\":\"general\","
                    + "\"current_interval_status\":3,"
                    + "\"current_weekly_status\":1,"
                    + "\"current_weekly_remaining_percent\":40}]}";
            BalanceSnapshot s = new StubMiniMax(resolvable("sk"), json).fetch(CTX);

            assertThat(s.available()).isTrue();
            assertThat(s.quotaPercent()).isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("两档均无百分比、也非不限量 → failed（不写 available=true 空值）")
        void failsWhenNoPercent() {
            String json = "{\"model_remains\":[{\"model_name\":\"general\","
                    + "\"current_interval_status\":1,\"current_weekly_status\":1}]}";
            BalanceSnapshot s = new StubMiniMax(resolvable("sk"), json).fetch(CTX);

            assertThat(s.available()).isFalse();
            assertThat(s.error()).contains("剩余百分比");
        }

        @Test
        @DisplayName("缺 general 项 → failed")
        void failsWhenNoGeneral() {
            String json = "{\"model_remains\":[{\"model_name\":\"video\",\"current_interval_status\":1}]}";
            BalanceSnapshot s = new StubMiniMax(resolvable("sk"), json).fetch(CTX);

            assertThat(s.available()).isFalse();
            assertThat(s.error()).contains("model_remains.general");
        }
    }

    @Nested
    @DisplayName("博查（金额型，主域失败回落备域）")
    class Bocha {

        @Test
        @DisplayName("主域正常 → 取 data.remaining（单位：元）")
        void parsesAmount() {
            String json = "{\"code\":0,\"data\":{\"remaining\":50.50}}";
            StubBocha p = new StubBocha(resolvable("sk"), Optional.of(json));

            BalanceSnapshot s = p.fetch(CTX);

            assertThat(s.available()).isTrue();
            assertThat(s.amount()).isEqualByComparingTo("50.50");
            assertThat(s.quotaPercent()).isNull();
            assertThat(p.urls).hasSize(1);
        }

        @Test
        @DisplayName("主域失败 → 回落备域成功")
        void fallsBackToSecondDomain() {
            String json = "{\"code\":0,\"data\":{\"remaining\":12.00}}";
            StubBocha p = new StubBocha(resolvable("sk"), Optional.empty(), Optional.of(json));

            BalanceSnapshot s = p.fetch(CTX);

            assertThat(s.available()).isTrue();
            assertThat(s.amount()).isEqualByComparingTo("12.00");
            assertThat(p.urls).hasSize(2);
            assertThat(p.urls.get(0)).contains("api.bochaai.com");
            assertThat(p.urls.get(1)).contains("api.bocha.cn");
        }

        @Test
        @DisplayName("两域均失败 → failed，不回填 0")
        void failsWhenBothDomainsFail() {
            StubBocha p = new StubBocha(resolvable("sk"), Optional.empty(), Optional.empty());
            BalanceSnapshot s = p.fetch(CTX);

            assertThat(s.available()).isFalse();
            assertThat(s.amount()).isNull();
            assertThat(s.error()).contains("请求失败");
        }

        @Test
        @DisplayName("响应缺 data.remaining → failed")
        void failsWhenNoRemaining() {
            StubBocha p = new StubBocha(resolvable("sk"), Optional.of("{\"code\":0,\"data\":{}}"));
            BalanceSnapshot s = p.fetch(CTX);

            assertThat(s.available()).isFalse();
            assertThat(s.error()).contains("data.remaining");
        }
    }
}
