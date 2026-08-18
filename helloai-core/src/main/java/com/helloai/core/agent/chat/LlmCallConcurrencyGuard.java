package com.helloai.core.agent.chat;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 平台内 LLM 调用并发限流（对话并发优化 B 项）。
 *
 * <p>真实 Provider 模式下所有 LLM 调用（对话澄清/拆解/审查/执行）共用同一批 API Key，
 * 上游有 RPS 限流；本组件以 JVM 内信号量把在途 LLM 调用数压到配置上限，
 * 避免并发打爆上游触发 429 限流。单实例部署信号量即全局；多实例部署需升级为
 * 分布式限流（Redis），当前不做。</p>
 *
 * <p>限流语义：许可获取带超时，超时抛 {@link BizException} 提示稍后重试，
 * 避免请求无限阻塞在信号量上占满 Tomcat 线程。mock 模式不经过本组件
 * （本地即时返回，无上游压力，由调用方 {@code AgentChatClientServiceImpl} 跳过）。</p>
 */
@Slf4j
@Component
public class LlmCallConcurrencyGuard {

    /** 信号量；null 表示未启用限流（配置 &lt;=0）。 */
    private final Semaphore semaphore;

    /** 许可获取超时（秒）。 */
    private final long acquireTimeoutSeconds;

    public LlmCallConcurrencyGuard(AgentExecutionProperties properties) {
        int permits = properties.getMaxConcurrentLlmCalls();
        this.semaphore = permits > 0 ? new Semaphore(permits, true) : null;
        this.acquireTimeoutSeconds = Math.max(properties.getLlmAcquireTimeoutSeconds(), 1);
        log.info("LLM 并发限流初始化: permits={}, acquireTimeoutSeconds={}",
                permits, acquireTimeoutSeconds);
    }

    /**
     * 获取一个 LLM 调用许可（阻塞等待，超时抛 BizException）。
     *
     * @throws BizException 等待超时或线程被中断
     */
    public void acquire() {
        if (semaphore == null) {
            return;
        }
        try {
            if (!semaphore.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS)) {
                throw new BizException("LLM 调用并发过高（上限 " + semaphore.availablePermits() + "），请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("等待 LLM 并发许可被中断");
        }
    }

    /** 释放一个 LLM 调用许可（与 {@link #acquire()} 成对使用，调用方 finally 中释放）。 */
    public void release() {
        if (semaphore != null) {
            semaphore.release();
        }
    }

    /** 当前可用许可数（监控/测试用）。 */
    public int availablePermits() {
        return semaphore != null ? semaphore.availablePermits() : Integer.MAX_VALUE;
    }
}
