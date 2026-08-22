package com.helloai.core.agent.chat.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link ProviderChatModelCache} 单元测试（N9 Phase 2B）。
 *
 * <p>覆盖：</p>
 * <ul>
 *     <li>首次调用触发 loader，缓存写回；</li>
 *     <li>同 key 二次调用直接命中，loader 不再触发；</li>
 *     <li>不同 key 完全隔离；</li>
 *     <li>loader 抛异常时不会写入缓存；</li>
 *     <li>{@code evict} / {@code clear} 操作正确性；</li>
 *     <li>{@link ProviderChatModelCache#buildKey} provider 大小写归一，apiKey 不同 → key 不同，model 不同 → key 不同。</li>
 * </ul>
 */
@DisplayName("ProviderChatModelCache")
class ProviderChatModelCacheTest {

    private ProviderChatModelCache cache;

    @BeforeEach
    void setUp() {
        cache = new ProviderChatModelCache();
    }

    @Nested
    @DisplayName("getOrCompute 缓存语义")
    class GetOrCompute {

        @Test
        @DisplayName("首次调用触发 loader 并写回缓存")
        void shouldLoadAndCacheFirst() {
            ChatModel m = mock(ChatModel.class);

            ChatModel result = cache.getOrCompute("k1", () -> m);

            assertThat(result).isSameAs(m);
            assertThat(cache.size()).isEqualTo(1);
            assertThat(cache.get("k1")).isSameAs(m);
        }

        @Test
        @DisplayName("同 key 二次调用直接命中，loader 只触发一次")
        void shouldHitCacheOnSecondCall() {
            ChatModel m = mock(ChatModel.class);

            ChatModel first = cache.getOrCompute("k1", () -> m);
            ChatModel second = cache.getOrCompute("k1", () -> {
                throw new AssertionError("loader 不应再次触发");
            });

            assertThat(first).isSameAs(second).isSameAs(m);
            assertThat(cache.size()).isEqualTo(1);
        }

        @Test
        @DisplayName("不同 key 之间互不串扰")
        void shouldIsolateByKey() {
            ChatModel m1 = mock(ChatModel.class);
            ChatModel m2 = mock(ChatModel.class);

            cache.getOrCompute("k1", () -> m1);
            cache.getOrCompute("k2", () -> m2);

            assertThat(cache.get("k1")).isSameAs(m1);
            assertThat(cache.get("k2")).isSameAs(m2);
            assertThat(cache.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("loader 抛异常时不会写入缓存")
        void shouldNotCacheWhenLoaderFails() {
            assertThatThrownBy(() -> cache.getOrCompute("k1", () -> {
                throw new IllegalStateException("boom");
            })).hasMessage("boom");

            assertThat(cache.size()).isEqualTo(0);
            assertThat(cache.get("k1")).isNull();
        }

        @Test
        @DisplayName("loader 返回 null 时不写入缓存")
        void shouldNotCacheNullValue() {
            ChatModel result = cache.getOrCompute("k1", () -> null);

            assertThat(result).isNull();
            assertThat(cache.size()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("evict / clear")
    class Eviction {

        @Test
        @DisplayName("evict 移除 key 并返回旧值")
        void shouldEvictKey() {
            ChatModel m = mock(ChatModel.class);
            cache.put("k1", m);

            ChatModel removed = cache.evict("k1");

            assertThat(removed).isSameAs(m);
            assertThat(cache.size()).isEqualTo(0);
            assertThat(cache.get("k1")).isNull();
        }

        @Test
        @DisplayName("evict 不存在的 key 返回 null，不抛异常")
        void shouldEvictMissingKeySafely() {
            ChatModel removed = cache.evict("not-exist");

            assertThat(removed).isNull();
            assertThat(cache.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("clear 清空全部")
        void shouldClearAll() {
            cache.put("k1", mock(ChatModel.class));
            cache.put("k2", mock(ChatModel.class));
            assertThat(cache.size()).isEqualTo(2);

            cache.clear();
            assertThat(cache.size()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("buildKey 键构造")
    class KeyBuilding {

        @Test
        @DisplayName("provider 大小写不敏感")
        void shouldNormalizeProviderCase() {
            String upper = ProviderChatModelCache.buildKey("DeepSeek", "k", "https://api.example.com/v1");
            String lower = ProviderChatModelCache.buildKey("deepseek", "k", "https://api.example.com/v1");
            assertThat(upper).isEqualTo(lower);
        }

        @Test
        @DisplayName("不同 apiKey 产生不同 key")
        void shouldDistinguishApiKey() {
            String a = ProviderChatModelCache.buildKey("deepseek", "key-a", null);
            String b = ProviderChatModelCache.buildKey("deepseek", "key-b", null);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("不同 baseUrl 产生不同 key")
        void shouldDistinguishBaseUrl() {
            String a = ProviderChatModelCache.buildKey("deepseek", "k", "https://api.deepseek.com");
            String b = ProviderChatModelCache.buildKey("deepseek", "k", "https://api.deepseek.com/v2");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("null/blank apiKey 一律归到 no-key")
        void shouldGroupNullOrBlankKey() {
            String nullKey = ProviderChatModelCache.buildKey("deepseek", null, "x");
            String blankKey = ProviderChatModelCache.buildKey("deepseek", "  ", "x");
            String noKey = ProviderChatModelCache.buildKey("deepseek", "no-key", "x");
            // null 和 blank 同 hash；同值非空时 hash 与 "no-key" 字面量不同
            assertThat(nullKey).isEqualTo(blankKey);
            assertThat(nullKey).isNotEqualTo(noKey);
        }

        @Test
        @DisplayName("不同 model 产生不同 key（Agent 改模型后自动分桶）")
        void shouldDistinguishModel() {
            String a = ProviderChatModelCache.buildKey("deepseek", "k", "https://api.deepseek.com", "deepseek", "deepseek-chat");
            String b = ProviderChatModelCache.buildKey("deepseek", "k", "https://api.deepseek.com", "deepseek", "deepseek-reasoner");
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("model null/blank 与显式 default 同桶（默认模型桶）")
        void shouldGroupNullOrBlankModel() {
            String nullModel = ProviderChatModelCache.buildKey("deepseek", "k", "x", "deepseek", null);
            String blankModel = ProviderChatModelCache.buildKey("deepseek", "k", "x", "deepseek", "  ");
            String explicitDefault = ProviderChatModelCache.buildKey("deepseek", "k", "x", "deepseek", "default");
            // model 段直接拼接：null/blank 归 "default" 字面量，与显式 default 同桶（默认模型桶）
            assertThat(nullModel).isEqualTo(blankModel).isEqualTo(explicitDefault);
        }

        @Test
        @DisplayName("model 大小写敏感（不归一化，避免大小写不同配置误共享）")
        void shouldKeepModelCase() {
            String lower = ProviderChatModelCache.buildKey("moonshot", "k", "x", "OPENAI_COMPATIBLE", "moonshot-v1-8k");
            String upper = ProviderChatModelCache.buildKey("moonshot", "k", "x", "OPENAI_COMPATIBLE", "Moonshot-V1-8K");
            assertThat(lower).isNotEqualTo(upper);
        }
    }

    @Nested
    @DisplayName("loader 行为（mock 触发验证）")
    class LoaderVerification {

        @Test
        @DisplayName("miss → loader 只触发一次；hit 不再触发")
        void shouldInvokeLoaderOnlyOnMiss() {
            ChatModel m = mock(ChatModel.class);
            java.util.concurrent.atomic.AtomicInteger callCount =
                    new java.util.concurrent.atomic.AtomicInteger();

            cache.getOrCompute("k1", () -> {
                callCount.incrementAndGet();
                return m;
            });
            // hit
            cache.getOrCompute("k1", () -> {
                callCount.incrementAndGet();
                return m;
            });
            cache.getOrCompute("k1", () -> {
                callCount.incrementAndGet();
                return m;
            });

            assertThat(callCount.get()).isEqualTo(1);
        }
    }
}
