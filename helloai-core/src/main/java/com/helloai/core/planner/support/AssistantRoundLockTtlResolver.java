package com.helloai.core.planner.support;

import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.config.AssistantChatProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 助理对话整轮锁 TTL 解析器（N-1，2026-11-03）。
 *
 * <p><b>不变量</b>：助理轮在<b>同步 LLM 调用期间持锁</b>，锁有效期不得短于单次 LLM 的最坏时长
 * ——后者由 provider 读超时给出。若锁先过期，另一请求的 {@code tryLock} 会成功抢占，
 * 两轮并发写回（正是加锁要防的场景）。原实现硬编码 {@code 180s}，而 local/dev 的 provider
 * 读超时为 {@code 600000ms}（600s）⇒ 踩中该不变量。</p>
 *
 * <p>语义与 {@code ReviewLockTtlResolver}（MED-3）一致：
 * {@code helloai.assistant.round-lock-ttl-seconds} 是<b>下限</b>，
 * 有效值 = {@code max(配置值, 各 provider 读超时最大值 + 1s 余量)}。
 * 方向唯一（只能调大、不能调小），故自动抬到合法值是安全的；同时 WARN 留痕。
 * 配置非正（{@code <= 0}）属无意义输入，启动时 fail-fast。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantRoundLockTtlResolver {

    /** 读超时与锁 TTL 之间保留的余量（秒）：覆盖调用结束到锁释放之间的落库 / 日志开销。 */
    private static final long TTL_MARGIN_SECONDS = 1;

    private final AssistantChatProperties assistantChatProperties;
    private final AgentProviderProperties agentProviderProperties;

    /** 有效 TTL（秒）；启动时日志记录最终取值与是否被抬高。 */
    private volatile long effectiveTtlSeconds;

    @PostConstruct
    public void init() {
        long configured = assistantChatProperties.getRoundLockTtlSeconds();
        if (configured <= 0) {
            throw new IllegalStateException(
                    "helloai.assistant.round-lock-ttl-seconds 必须为正数，当前=" + configured);
        }
        long required = requiredMinTtlSeconds();
        this.effectiveTtlSeconds = Math.max(configured, required);
        if (required > configured) {
            log.warn("助理轮锁 TTL 配置值低于 provider 读超时，已自动抬高（配置值视为下限）："
                            + "configured={}s -> effective={}s (required>={}s，"
                            + "因锁不得短于其守护的 LLM 调用，否则锁过期后可被另一轮抢占)",
                    configured, effectiveTtlSeconds, required);
        } else {
            log.info("助理轮锁 TTL: configured={}s, effective={}s (provider 读超时上界 {}s)",
                    configured, effectiveTtlSeconds, required - TTL_MARGIN_SECONDS);
        }
    }

    /**
     * 有效锁 TTL（秒）——唯一读取口。
     *
     * <p>{@code @PostConstruct} 未执行（单元测试直连 / 非 Spring 上下文）时按需即时解析，
     * 避免返回 0 导致 {@code tryLock(…, 0, …)} 语义异常。</p>
     */
    public long effectiveTtlSeconds() {
        long cached = effectiveTtlSeconds;
        if (cached > 0) {
            return cached;
        }
        return Math.max(assistantChatProperties.getRoundLockTtlSeconds(), requiredMinTtlSeconds());
    }

    /** 不变量要求的最小 TTL（秒）= 各 provider 读超时最大值 + 余量；无配置时返回 0（不抬高）。 */
    private long requiredMinTtlSeconds() {
        long maxReadTimeoutMs = maxReadTimeoutMs();
        if (maxReadTimeoutMs <= 0) {
            return 0L;
        }
        long maxReadTimeoutSeconds = (maxReadTimeoutMs + 999L) / 1000L;
        return maxReadTimeoutSeconds + TTL_MARGIN_SECONDS;
    }

    private long maxReadTimeoutMs() {
        if (agentProviderProperties.getProviders() == null
                || agentProviderProperties.getProviders().isEmpty()) {
            return 0L;
        }
        long max = 0L;
        for (AgentProviderProperties.ProviderConfig config
                : agentProviderProperties.getProviders().values()) {
            if (config != null && config.getReadTimeoutMs() > max) {
                max = config.getReadTimeoutMs();
            }
        }
        return max;
    }
}