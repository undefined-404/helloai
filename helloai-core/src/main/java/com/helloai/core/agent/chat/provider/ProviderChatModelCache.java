package com.helloai.core.agent.chat.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Provider ChatModel 全局缓存（Phase 2B / N9 实现）。
 *
 * <p>目标：避免 AgentChatClientService 每条 LLM 调用都新建 DeepSeekChatModel/DeepSeekApi，
 * 减少 TCP 连接重建 / RestClient 连接池抖动 / 超时配置不一致等问题。</p>
 *
 * <h3>缓存粒度</h3>
 * <ul>
 *     <li>key：{@code provider + "::" + baseUrlHash + "::" + apiKeyHash(8 位前 16 进制)}</li>
 *     <li>不保存明文 apiKey：仅保留 SHA-256 hex 前 8 位作为指纹，避免缓存结构意外泄漏</li>
 *     <li>不同 provider 的 ChatModel 完全隔离</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>Spring AI 文档声明 {@code DeepSeekChatModel} / {@code OpenAiChatModel} 等核心实现为
 * thread-safe，因此并发复用同一 ChatModel 实例安全。缓存 key 粒度精细到 (provider, baseUrl, apiKey)
 * 三元组，避免 provider 误共享。</p>
 *
 * <h3>失效策略</h3>
 * <p>未引入容量上限（bounded cache）以保持简单。若监控发现内存压力显著时再升级为 LRU。</p>
 *
 * @see DeepSeekProviderChatClientFactory 使用方
 */
@Slf4j
@Component
public class ProviderChatModelCache {

    private final ConcurrentMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * 经典 cache-aside 读取：命中直接返回，未命中通过 {@code loader} 计算并写回。
     *
     * <p>{@code loader} 抛异常时不会写入缓存，下次调用仍会触发 loader；这是有意为之：
     * 避免把一次性故障状态固化在缓存里。</p>
     */
    public ChatModel getOrCompute(String key, Supplier<ChatModel> loader) {
        ChatModel hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        ChatModel loaded = loader.get();
        if (loaded == null) {
            return null;
        }
        ChatModel prev = cache.putIfAbsent(key, loaded);
        if (prev == null) {
            log.info("Provider ChatModel 缓存新增 key={} (cacheSize={})", key, cache.size());
            return loaded;
        }
        // race：另一个线程先到 put 了同一 key，复用它，避免重复构建
        log.debug("Provider ChatModel 缓存竞争命中 key={}，复用先到者", key);
        return prev;
    }

    /**
     * 直接写回缓存（很少用，主要供测试 / 替换实例时使用）。
     */
    public ChatModel put(String key, ChatModel model) {
        Objects.requireNonNull(model, "model");
        ChatModel prev = cache.put(key, model);
        log.info("Provider ChatModel 缓存覆盖 key={} (cacheSize={})", key, cache.size());
        return prev;
    }

    public ChatModel get(String key) {
        return cache.get(key);
    }

    public ChatModel evict(String key) {
        ChatModel removed = cache.remove(key);
        if (removed != null) {
            log.info("Provider ChatModel 缓存驱逐 key={} (cacheSize={})", key, cache.size());
        }
        return removed;
    }

    public void clear() {
        cache.clear();
        log.warn("Provider ChatModel 缓存全部清空");
    }

    public int size() {
        return cache.size();
    }

    /**
     * 构造缓存 key。
     *
     * @param provider provider 名（大小写不敏感）
     * @param apiKey   明文 apiKey（仅用于 hash，不存储）
     * @param baseUrl  base url（允许 null，对应 null safeHash）
     */
    public static String buildKey(String provider, String apiKey, String baseUrl) {
        String normalizedProvider = provider != null ? provider.toLowerCase(Locale.ROOT) : "unknown";
        String apiKeyFingerprint = sha256Prefix8(apiKey);
        String baseUrlFingerprint = baseUrl != null && !baseUrl.isBlank()
                ? Integer.toHexString(baseUrl.hashCode())
                : "default";
        return normalizedProvider + "::" + baseUrlFingerprint + "::" + apiKeyFingerprint;
    }

    private static String sha256Prefix8(String input) {
        if (input == null || input.isBlank()) {
            return "no-key";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by JDK, never absent; fall back to hashCode
            return Integer.toHexString(input.hashCode());
        }
    }
}
