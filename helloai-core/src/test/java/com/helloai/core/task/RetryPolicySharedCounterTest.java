package com.helloai.core.task;

import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.constant.RetryPolicy;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 A3 坑点 3「单一权威」：共享重试预算（attempt_total）判定测试。
 *
 * <p>模拟「Executor 重试 N 次 → 各重试层检查共享计数器已达上限 → 不再重试」链路：
 * 计数器统一存在 {@code sub_task.attempt_total}，判定语义收敛在 {@link RetryPolicy}，
 * 任何一层重试前都必须先过 {@link RetryPolicy#exceedsMax}。</p>
 */
class RetryPolicySharedCounterTest {

    // ══════════════════════════════════════════════════════════
    //  预算判定：attempt >= max 即停
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("attempt_total 达到上限 → 禁止继续重试（熔断）")
    void shouldStopWhenAttemptTotalReachesMax() {
        SubTask subTask = new SubTask();
        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setAttemptTotal(5);
        // 共享预算达上限：任何后续重试入口都应熔断
        assertThat(RetryPolicy.exceedsMax(subTask.getAttemptTotal(), 5)).isTrue();
    }

    @Test
    @DisplayName("Executor/MQ 各层重试 3 次后共享计数器为 3，仍低于上限 5 → 可继续重试")
    void shouldAllowRetryWhenBelowMax() {
        SubTask subTask = new SubTask();
        subTask.setAttemptTotal(3);
        assertThat(RetryPolicy.exceedsMax(subTask.getAttemptTotal(), 5)).isFalse();
        // 累加一次后仍低于上限
        subTask.setAttemptTotal(4);
        assertThat(RetryPolicy.exceedsMax(subTask.getAttemptTotal(), 5)).isFalse();
    }

    @Test
    @DisplayName("maxAttempts <= 0 为熔断禁用逃生口，永不熔断")
    void shouldNeverStopWhenCircuitBreakerDisabled() {
        assertThat(RetryPolicy.exceedsMax(100, 0)).isFalse();
        assertThat(RetryPolicy.exceedsMax(100, -1)).isFalse();
    }

    @Test
    @DisplayName("空值计数按 0 处理，未达上限可重试")
    void shouldTreatNullAttemptAsZero() {
        SubTask subTask = new SubTask();
        assertThat(subTask.getAttemptTotal()).isNull();
        assertThat(RetryPolicy.exceedsMax(0, 5)).isFalse();
    }

    // ══════════════════════════════════════════════════════════
    //  可重试错误分类
    // ══════════════════════════════════════════════════════════

    @Test
    @DisplayName("连接/超时/Agent 不可用类错误可重试")
    void shouldClassifyRetryableErrors() {
        assertThat(RetryPolicy.isRetryable(new IOException())).isTrue();
        assertThat(RetryPolicy.isRetryable(new SocketTimeoutException())).isTrue();
        assertThat(RetryPolicy.isRetryable(new AgentUnavailableException("agent down"))).isTrue();
        // 包装链中的可重试根因同样可重试
        assertThat(RetryPolicy.isRetryable(
                new IllegalStateException("wrapped", new IOException("conn reset")))).isTrue();
    }

    @Test
    @DisplayName("业务语义错误不可重试（重试只会放大错误）")
    void shouldClassifyNonRetryableErrors() {
        assertThat(RetryPolicy.isRetryable(new IllegalArgumentException("bad param"))).isFalse();
        assertThat(RetryPolicy.isRetryable(null)).isFalse();
    }
}