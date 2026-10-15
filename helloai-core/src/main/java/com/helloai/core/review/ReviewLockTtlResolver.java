package com.helloai.core.review;

import com.helloai.common.config.AgentProviderProperties;
import com.helloai.common.config.ReviewProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 核验互斥锁 TTL 解析器（`MED-3`，2026-10-15）。
 *
 * <p><b>不变量</b>：核验锁在 LLM 调用期间持有，故「锁有效期」<b>不得短于</b>「单次核验
 * LLM 调用的最坏时长」——后者由 provider 读超时给出。若锁先过期，L1 AFTER_COMMIT 事件 /
 * L2 MQ consumer / L3 孤儿扫描三路触发源可抢到<b>已过期锁</b>对同一子任务<b>并发核验</b>
 * ⇒ 重复 {@code review_record}（画像 reviewer 维度重复计数），双审场景下还可能出现
 * pass=false 双返工竞态。原实现硬编码 {@code 120s}，而读取超时默认 {@code 180s}
 * ⇒ 踩中该不变量（单审路径无 deadline，可合法跑到 180s）。</p>
 *
 * <p><b>★为什么是「取较大值」而不是「配置不对就 fail-fast」</b>：本仓
 * {@code read-timeout-ms} 是<b>按 profile 分档</b>的 —— 默认档 {@code 180000}（3 分钟），
 * 而 {@code application-dev.yml} 为整合报告长输出设为 {@code 600000}（10 分钟，且该值
 * <b>已入库</b>）。若采用 fail-fast，则必须让每个 profile 各自同步维护一个锁 TTL 值，
 * 而 {@code application-dev.yml} 被 {@code git update-index --skip-worktree} 标记为
 * <b>本地私有、禁止提交</b> ⇒ 无法把 dev 的覆盖值写进仓库 ⇒ <b>fresh clone 的 dev
 * profile 将直接起不来</b>。</p>
 *
 * <p><b>故语义定为：{@code helloai.review.lock-ttl-seconds} 是「下限」，有效值 =
 * {@code max(配置值, 各 provider 读超时最大值 + 1s 余量)}</b>。理由：该不变量不是「偏好」
 * 而是<b>正确性约束</b> —— 锁不能比它守护的操作更短，方向唯一（只能调大、不能调小），
 * 故自动抬到合法值是安全的；同时 WARN 留痕，避免配置意图被静默掩盖。</p>
 *
 * <p>唯一保留的 fail-fast：配置值非正（{@code <= 0}）——那是无意义的输入，
 * 且说明配置者对该项理解有误，应显式报错而非猜测。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewLockTtlResolver {

    /** 读超时与锁 TTL 之间保留的余量（秒）：覆盖调用结束到锁释放之间的落库 / 时间线开销。 */
    private static final long TTL_MARGIN_SECONDS = 1;

    private final ReviewProperties reviewProperties;
    private final AgentProviderProperties agentProviderProperties;

    /** 有效 TTL（秒）；启动时日志记录最终取值与是否被抬高。 */
    private volatile long effectiveTtlSeconds;

    @PostConstruct
    public void init() {
        long configured = reviewProperties.getLockTtlSeconds();
        if (configured <= 0) {
            throw new IllegalStateException(
                    "helloai.review.lock-ttl-seconds 必须为正数，当前=" + configured);
        }

        long required = requiredMinTtlSeconds();
        this.effectiveTtlSeconds = Math.max(configured, required);

        if (required > configured) {
            log.warn("核验锁 TTL 配置值低于 provider 读超时，已自动抬高（配置值视为下限）："
                            + "configured={}s -> effective={}s (required>={}s，"
                            + "因锁不得短于其守护的 LLM 调用，否则锁过期后 L1/L2/L3 可抢过期锁并发核验)",
                    configured, effectiveTtlSeconds, required);
        } else {
            log.info("核验锁 TTL: configured={}s, effective={}s (provider 读超时上界 {}s)",
                    configured, effectiveTtlSeconds, required - TTL_MARGIN_SECONDS);
        }
    }

    /**
     * 有效锁 TTL（秒）—— 唯一读取口，供核验互斥锁 {@code tryLock} 使用。
     *
     * <p>{@code @PostConstruct} 未执行（单元测试直连 / 非 Spring 上下文）时按需即时解析，
     * 避免返回 0 导致 {@code tryLock(0, 0, …)} 语义异常。</p>
     */
    public long effectiveTtlSeconds() {
        long cached = effectiveTtlSeconds;
        if (cached > 0) {
            return cached;
        }
        return Math.max(reviewProperties.getLockTtlSeconds(), requiredMinTtlSeconds());
    }

    /** 不变量要求的最小 TTL（秒）= 各 provider 读超时最大值 + 余量；无 provider 配置时返回 0（不抬高）。 */
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
