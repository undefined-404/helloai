package com.helloai.core.agent.runtime.cancel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CancellationToken} / {@link LoopCancellation} 单元测试（REF-8.6）。
 */
@DisplayName("CancellationToken / LoopCancellation（REF-8.6）")
class CancellationTokenTest {

    @Test
    @DisplayName("none()：恒不取消，throwIfCancelled 为空操作，且为共享常量（身份可比较）")
    void noneIsNeverCancelled() {
        assertThat(CancellationToken.none().isCancelled()).isFalse();
        assertThatCode(() -> CancellationToken.none().throwIfCancelled()).doesNotThrowAnyException();
        assertThat(CancellationToken.none()).isSameAs(CancellationToken.NONE);
    }

    @Test
    @DisplayName("deadlineAfter(<=0) 退化为 none（同身份）——「不限」不产生额外装饰")
    void nonPositiveDeadlineDegradesToNone() {
        assertThat(CancellationToken.deadlineAfter(0)).isSameAs(CancellationToken.NONE);
        assertThat(CancellationToken.deadlineAfter(-5)).isSameAs(CancellationToken.NONE);
        assertThat(LoopCancellation.of(0, 0)).isSameAs(LoopCancellation.none());
        assertThat(LoopCancellation.of(0, 0).active()).isFalse();
    }

    @Test
    @DisplayName("deadlineAfter(N)：未到期不取消，到期后自动取消并带原因")
    void deadlineTokenExpires() throws Exception {
        CancellationToken token = CancellationToken.deadlineAfter(1);
        assertThat(token.isCancelled()).isFalse();
        assertThatCode(token::throwIfCancelled).doesNotThrowAnyException();

        Thread.sleep(1100L);

        assertThat(token.isCancelled()).isTrue();
        assertThat(token.reason()).contains("turn deadline exceeded");
        assertThatThrownBy(token::throwIfCancelled)
                .isInstanceOf(AgentCancelledException.class)
                .hasMessageContaining("turn deadline exceeded");
    }

    @Test
    @DisplayName("mutable()：外部可主动取消（跨线程可见），取消后 throwIfCancelled 抛带原因的异常")
    void mutableTokenCanBeCancelled() throws Exception {
        CancellationToken.Mutable token = CancellationToken.mutable();
        assertThat(token.isCancelled()).isFalse();

        Thread canceller = new Thread(() -> token.cancel("人工中止"));
        canceller.start();
        canceller.join();

        assertThat(token.isCancelled()).isTrue();
        assertThat(token.reason()).isEqualTo("人工中止");
        assertThatThrownBy(token::throwIfCancelled)
                .isInstanceOf(AgentCancelledException.class)
                .hasMessage("人工中止");
    }

    @Test
    @DisplayName("LoopCancellation.active()：仅工具超时开启 / 仅截止时间开启 都为 true")
    void loopCancellationActiveWhenEitherCapabilityOn() {
        assertThat(LoopCancellation.of(60, 0).active()).isTrue();
        assertThat(LoopCancellation.of(0, 1000).active()).isTrue();
        assertThat(LoopCancellation.none().active()).isFalse();
    }
}
