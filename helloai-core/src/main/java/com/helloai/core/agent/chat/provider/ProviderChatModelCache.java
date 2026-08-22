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
 *     <li>key：{@code protocolType + provider + baseUrlHash + apiKeyHash + model}（五元组）</li>
 *     <li>不保存明文 apiKey：仅保留 SHA-256 hex 前 8 位作为指纹，避免缓存结构意外泄漏</li>
 *     <li>不同 provider / protocolType / model 的 ChatModel 完全隔离</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>Spring AI 文档声明 {@code DeepSeekChatModel} / {@code OpenAiChatModel} 等核心实现为
 * thread-safe，因此并发复用同一 ChatModel 实例安全。缓存 key 粒度精细到
 * (provider, baseUrl, apiKey, protocolType, model) 五元组，避免 provider 误共享。</p>
 *
 * <h3>失效策略</h3>
 * <p>key 含 model 维度：Agent 修改 model_type 后自动落入新桶重建 ChatModel，无需重启（2026-08-22
 * 修复产品缺陷——原 key 不含 model，改模型不生效直到重启）。未引入容量上限（bounded cache）以
 * 保持简单；若监控发现内存压力显著时再升级为 LRU。</p>
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
     * 构造缓存 key（兼容旧签名，protocolType 默认为 {@code openai-compatible}，model 归 default 桶）。
     *
     * @param provider provider 名（大小写不敏感）
     * @param apiKey   明文 apiKey（仅用于 hash，不存储）
     * @param baseUrl  base url（允许 null，对应 null safeHash）
     */
    public static String buildKey(String provider, String apiKey, String baseUrl) {
        return buildKey(provider, apiKey, baseUrl, "openai-compatible", null);
    }

    /**
     * 构造缓存 key（兼容旧签名，model 归 default 桶）。
     *
     * <p>OpenAI 兼容与 Anthropic 兼容使用不同的 ChatModel 实现（OpenAiChatModel /
     * AnthropicChatModel），不能复用同一实例，故缓存 key 需包含 protocolType。</p>
     *
     * @param provider     provider 名（大小写不敏感）
     * @param apiKey       明文 apiKey（仅用于 hash，不存储）
     * @param baseUrl      base url（允许 null，对应 null safeHash）
     * @param protocolType 协议类型（OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE / deepseek 等）
     */
    public static String buildKey(String provider, String apiKey, String baseUrl, String protocolType) {
        return buildKey(provider, apiKey, baseUrl, protocolType, null);
    }

    /**
     * 构造缓存 key（五元组主签名，2026-08-22 起包含 model 维度）。
     *
     * <p>原四元组 (provider, baseUrl, apiKey, protocolType) 不含 model：Agent 修改 model_type 后
     * 命中同一 key 复用旧 ChatModel 实例，新模型配置不生效直到重启（实测 qwen3.8-Max 改
     * qwen3.7-plus 后请求仍 404 qwen3.8-Max）。加入 model 维度后，模型变更自动落入新桶重建实例，
     * 无需重启。model 段按原样参与 key（不归一化大小写）：模型名可能大小写敏感（如 MiniMax-Text-01），
     * 归一化会导致大小写不同的配置误共享同一实例；null/blank 归 default 桶。</p>
     *
     * @param provider     provider 名（大小写不敏感）
     * @param apiKey       明文 apiKey（仅用于 hash，不存储）
     * @param baseUrl      base url（允许 null，对应 null safeHash）
     * @param protocolType 协议类型（OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE / deepseek 等）
     * @param model        模型名（大小写敏感；null/blank 归 default 桶）
     */
    public static String buildKey(String provider, String apiKey, String baseUrl, String protocolType, String model) {
        String normalizedProvider = provider != null ? provider.toLowerCase(Locale.ROOT) : "unknown";
        String protocolSegment = protocolType != null && !protocolType.isBlank()
                ? protocolType.toLowerCase(Locale.ROOT)
                : "default";
        String modelSegment = model != null && !model.isBlank() ? model : "default";
        String apiKeyFingerprint = sha256Prefix8(apiKey);
        String baseUrlFingerprint = baseUrl != null && !baseUrl.isBlank()
                ? Integer.toHexString(baseUrl.hashCode())
                : "default";
        return protocolSegment + "::" + normalizedProvider + "::" + baseUrlFingerprint + "::" + apiKeyFingerprint
                + "::" + modelSegment;
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
