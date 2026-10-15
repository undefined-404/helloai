package com.helloai.core.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConcurrencyGuardedChatModel} 单测（`G8`，2026-10-15）。
 *
 * <p>覆盖：真实模式占/放许可（含异常路径也要放）、mock 模式纯委托不占许可、流式终止释放。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("G8 执行链 LLM 并发限流装饰器")
class ConcurrencyGuardedChatModelTest {

    @Mock
    private ChatModel delegate;
    @Mock
    private LlmCallConcurrencyGuard guard;

    private static final Prompt PROMPT = new Prompt("hi");

    @Test
    @DisplayName("真实模式：call 前后成对 acquire/release（执行链此前完全绕过闸门）")
    void throttledCallAcquiresAndReleases() {
        ChatResponse expected = new ChatResponse(java.util.List.of());
        when(delegate.call(any(Prompt.class))).thenReturn(expected);

        ConcurrencyGuardedChatModel model = new ConcurrencyGuardedChatModel(delegate, guard, true);

        assertThat(model.call(PROMPT)).isSameAs(expected);
        verify(guard).acquire();
        verify(guard).release();
    }

    @Test
    @DisplayName("真实模式：delegate 抛异常也必须释放许可（否则许可泄漏 ⇒ 后续调用 60s 超时后失败）")
    void releasesPermitOnDelegateFailure() {
        when(delegate.call(any(Prompt.class))).thenThrow(new IllegalStateException("upstream 500"));

        ConcurrencyGuardedChatModel model = new ConcurrencyGuardedChatModel(delegate, guard, true);

        assertThatThrownBy(() -> model.call(PROMPT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upstream 500");
        verify(guard).acquire();
        verify(guard).release();
    }

    @Test
    @DisplayName("mock 模式：纯委托，不占许可（本地即时返回无上游压力，与 chat 路径口径一致）")
    void mockModeDelegatesWithoutPermit() {
        ChatResponse expected = new ChatResponse(java.util.List.of());
        when(delegate.call(any(Prompt.class))).thenReturn(expected);

        ConcurrencyGuardedChatModel model = new ConcurrencyGuardedChatModel(delegate, guard, false);

        assertThat(model.call(PROMPT)).isSameAs(expected);
        verify(guard, never()).acquire();
        verify(guard, never()).release();
    }

    @Test
    @DisplayName("真实模式：stream 在终止时释放（流式时长不定，不能靠同步 finally）")
    void throttledStreamReleasesOnTerminate() {
        Flux<ChatResponse> source = Flux.just(new ChatResponse(java.util.List.of()));
        when(delegate.stream(any(Prompt.class))).thenReturn(source);

        ConcurrencyGuardedChatModel model = new ConcurrencyGuardedChatModel(delegate, guard, true);

        // 订阅并消费完 → doFinally 触发释放
        model.stream(PROMPT).blockLast();

        verify(guard).acquire();
        verify(guard).release();
    }

    @Test
    @DisplayName("mock 模式：stream 纯委托，不占许可")
    void mockModeStreamDelegatesWithoutPermit() {
        when(delegate.stream(any(Prompt.class))).thenReturn(Flux.empty());

        ConcurrencyGuardedChatModel model = new ConcurrencyGuardedChatModel(delegate, guard, false);

        model.stream(PROMPT).blockLast();

        verify(guard, never()).acquire();
        verify(guard, never()).release();
    }
}
