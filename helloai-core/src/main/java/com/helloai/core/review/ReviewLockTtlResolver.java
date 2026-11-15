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
 * 期间全部 LLM 调用的最坏总时长」——后者由 provider 读超时 <b>× 调用次数</b>给出。若锁先过期，
 * L1 AFTER_COMMIT 事件 / L2 MQ consumer / L3 孤儿扫描三路触发源可抢到<b>已过期锁</b>对同一子任务
 * <b>并发核验</b> ⇒ 重复 {@code review_record}（画像 reviewer 维度重复计数），双审场景下还可能出现
 * pass=false 双返工竞态。原实现硬编码 {@code 120s}，而读取超时默认 {@code 180s}
 * ⇒ 踩中该不变量（单审路径无 deadline，可合法跑到 180s）。</p>
 *
 * <p><b>★调用次数不是恒为 1</b>：{@code helloai.review.unparseable-retry-enabled}（默认开启）
 * 让「调用成功但输出不可解析」再发一次，故最坏总时长 = 读超时 <b>× 2</b>。
 * 这是 `MED-3` 同一个不变量的<b>另一半</b>——只按单次读超时推导 TTL、却允许发两次调用，
 * 等价于把锁留在「不够长」的状态。故 {@link #maxLlmCallsPerReview()} 参与下限推导，
 * 开关关闭即回到单次口径（不写死 2，避免关掉重试后 TTL 被无谓拉长）。</p>
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
 * {@code max(配置值, 各 provider 读超时最大值 × 单次核验 LLM 调用次数 + 1s 余量)}</b>。理由：该不变量不是「偏好」
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
            log.warn("核验锁 TTL 配置值低于「provider 读超时 × 调用次数」，已自动抬高（配置值视为下限）："
                            + "configured={}s -> effective={}s (required>={}s, callsPerReview={}，"
                            + "因锁不得短于其守护的 LLM 调用，否则锁过期后 L1/L2/L3 可抢过期锁并发核验)",
                    configured, effectiveTtlSeconds, required, maxLlmCallsPerReview());
        } else {
            log.info("核验锁 TTL: configured={}s, effective={}s (provider 读超时上界 {}s × 调用 {} 次)",
                    configured, effectiveTtlSeconds,
                    (required - TTL_MARGIN_SECONDS) / maxLlmCallsPerReview(), maxLlmCallsPerReview());
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

    /**
     * 不变量要求的最小 TTL（秒）= 各 provider 读超时最大值 × 单次核验最多调用次数 + 余量；
     * 无 provider 配置时返回 0（不抬高）。
     */
    private long requiredMinTtlSeconds() {
        long maxReadTimeoutMs = maxReadTimeoutMs();
        if (maxReadTimeoutMs <= 0) {
            return 0L;
        }
        long maxReadTimeoutSeconds = (maxReadTimeoutMs + 999L) / 1000L;
        return maxReadTimeoutSeconds * maxLlmCallsPerReview() + TTL_MARGIN_SECONDS;
    }

    /**
     * 单次核验最多发起的 LLM 调用次数：不可解析即时重试开启 = 2（首轮 + 重试），关闭 = 1。
     *
     * <p>取值来自 {@code helloai.review.unparseable-retry-enabled}，<b>不写死 2</b>——
     * 关掉重试后锁 TTL 不应被无谓拉长（崩溃残留窗口更久）。</p>
     */
    private int maxLlmCallsPerReview() {
        return reviewProperties.isUnparseableRetryEnabled() ? 2 : 1;
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
