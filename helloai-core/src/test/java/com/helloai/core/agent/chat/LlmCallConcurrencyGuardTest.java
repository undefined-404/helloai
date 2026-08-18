package com.helloai.core.agent.chat;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmCallConcurrencyGuard 单元测试（对话并发优化 B 项）。
 *
 * <p>覆盖：许可获取/释放、并发超限排队超时抛 BizException、配置 &lt;=0 不限流、
 * 并发同时进入数不超过上限。</p>
 */
@DisplayName("LlmCallConcurrencyGuard")
class LlmCallConcurrencyGuardTest {

    private static AgentExecutionProperties props(int permits, long acquireTimeoutSeconds) {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        properties.setMaxConcurrentLlmCalls(permits);
        properties.setLlmAcquireTimeoutSeconds(acquireTimeoutSeconds);
        return properties;
    }

    @Test
    @DisplayName("获取与释放成对：release 后许可恢复")
    void shouldRestorePermitsAfterRelease() {
        LlmCallConcurrencyGuard guard = new LlmCallConcurrencyGuard(props(2, 1));

        guard.acquire();
        guard.acquire();
        assertThat(guard.availablePermits()).isZero();

        guard.release();
        assertThat(guard.availablePermits()).isEqualTo(1);
    }

    @Test
    @DisplayName("并发超过上限：等待超时抛 BizException（不无限阻塞）")
    void shouldTimeoutWhenOverLimit() throws Exception {
        LlmCallConcurrencyGuard guard = new LlmCallConcurrencyGuard(props(1, 1));
        guard.acquire(); // 占用唯一许可

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = pool.submit(() -> {
                guard.acquire();
                return null;
            });
            assertThatThrownBy(future::get)
                    .hasRootCauseInstanceOf(BizException.class)
                    .hasMessageContaining("并发过高");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("配置 <=0 表示不限流：acquire 直接返回，许可数恒为 MAX")
    void shouldNotThrottleWhenDisabled() {
        LlmCallConcurrencyGuard guard = new LlmCallConcurrencyGuard(props(0, 1));

        guard.acquire();
        guard.acquire();
        assertThat(guard.availablePermits()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("并发同时进入数不超过上限（N 个线程只放行 N 个）")
    void shouldNeverExceedPermitsUnderConcurrency() throws Exception {
        int permits = 4;
        int workers = 12;
        LlmCallConcurrencyGuard guard = new LlmCallConcurrencyGuard(props(permits, 5));

        CountDownLatch enterLatch = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);
        CountDownLatch doneLatch = new CountDownLatch(workers);

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (int i = 0; i < workers; i++) {
                pool.submit(() -> {
                    try {
                        guard.acquire();
                        enterLatch.await();
                        int now = inFlight.incrementAndGet();
                        maxInFlight.accumulateAndGet(now, Math::max);
                        Thread.sleep(50);
                        inFlight.decrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        guard.release();
                        doneLatch.countDown();
                    }
                });
            }
            Thread.sleep(200); // 等前 4 个拿到许可
            enterLatch.countDown(); // 放行全部工作线程
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(maxInFlight.get()).isLessThanOrEqualTo(permits);
        } finally {
            pool.shutdownNow();
        }
    }
}
