package com.helloai.core.agent.runtime.cancel;

import com.helloai.core.agent.tool.ToolExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取消装饰器单元测试（REF-8.6）：{@link CancellableChatModel} + {@link CancellableToolExecutor}。
 */
@DisplayName("取消装饰器（REF-8.6）")
class CancellableDecoratorsTest {

    // ────────────── CancellableChatModel ──────────────

    @Test
    @DisplayName("ChatModel：未取消时透传结果")
    void chatModelPassesThroughWhenNotCancelled() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse expected = mock(ChatResponse.class);
        when(delegate.call(any(Prompt.class))).thenReturn(expected);
        CancellableChatModel model = new CancellableChatModel(delegate, CancellationToken.mutable());

        assertThat(model.call(new Prompt("hi"))).isSameAs(expected);
    }

    @Test
    @DisplayName("ChatModel：调用前已取消 ⇒ 抛取消异常且不发起请求")
    void chatModelThrowsBeforeCallWhenCancelled() {
        ChatModel delegate = mock(ChatModel.class);
        CancellationToken.Mutable token = CancellationToken.mutable();
        token.cancel("提前取消");
        CancellableChatModel model = new CancellableChatModel(delegate, token);

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(AgentCancelledException.class)
                .hasMessage("提前取消");
        verify(delegate, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("ChatModel：调用期间被取消 ⇒ 在途结果不采用（也抛取消异常）")
    void chatModelDiscardsInflightResultWhenCancelledDuringCall() {
        CancellationToken.Mutable token = CancellationToken.mutable();
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.call(any(Prompt.class))).thenAnswer(invocation -> {
            token.cancel("在途取消");
            return mock(ChatResponse.class);
        });
        CancellableChatModel model = new CancellableChatModel(delegate, token);

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(AgentCancelledException.class)
                .hasMessage("在途取消");
    }

    @Test
    @DisplayName("ChatModel：delegate 为空 ⇒ 构造即拒绝（契约化失败，不做静默降级）")
    void chatModelRejectsNullDelegate() {
        assertThatThrownBy(() -> new CancellableChatModel(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ────────────── CancellableToolExecutor ──────────────

    @Test
    @DisplayName("ToolExecutor：超时关闭（<=0）⇒ 直调 delegate，零额外开销")
    void toolExecutorDirectDelegatesWhenTimeoutDisabled() {
        ToolExecutionResult expected = ToolExecutionResult.success("t", "ok");
        com.helloai.core.agent.tool.ToolExecutor delegate = (name, args) -> expected;
        CancellableToolExecutor executor =
                new CancellableToolExecutor(delegate, CancellationToken.mutable(), 0L);

        assertThat(executor.execute("t", "{}")).isSameAs(expected);
    }

    @Test
    @DisplayName("ToolExecutor：调用前已取消 ⇒ 抛取消异常（整链终止，非工具失败）")
    void toolExecutorThrowsWhenCancelled() {
        AtomicInteger calls = new AtomicInteger();
        com.helloai.core.agent.tool.ToolExecutor delegate = (name, args) -> {
            calls.incrementAndGet();
            return ToolExecutionResult.success(name, "ok");
        };
        CancellationToken.Mutable token = CancellationToken.mutable();
        token.cancel("取消");
        CancellableToolExecutor executor = new CancellableToolExecutor(delegate, token, 1000L);

        assertThatThrownBy(() -> executor.execute("t", "{}"))
                .isInstanceOf(AgentCancelledException.class);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("ToolExecutor：单工具超时 ⇒ 返回失败结果（不抛异常、不整链失败，交模型决策）")
    void toolExecutorReturnsFailureOnTimeout() {
        com.helloai.core.agent.tool.ToolExecutor delegate = (name, args) -> {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolExecutionResult.success(name, "late");
        };
        CancellableToolExecutor executor =
                new CancellableToolExecutor(delegate, CancellationToken.mutable(), 50L);

        ToolExecutionResult result = executor.execute("slow_tool", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("tool_timeout");
    }

    @Test
    @DisplayName("ToolExecutor：delegate 抛普通异常 ⇒ 转失败结果（不整链失败）")
    void toolExecutorConvertsDelegateFailure() {
        com.helloai.core.agent.tool.ToolExecutor delegate = (name, args) -> {
            throw new IllegalStateException("boom");
        };
        CancellableToolExecutor executor =
                new CancellableToolExecutor(delegate, CancellationToken.mutable(), 1000L);

        ToolExecutionResult result = executor.execute("t", "{}");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("ToolExecutor：delegate 内部抛取消异常 ⇒ 透传（取消不被降级为工具失败）")
    void toolExecutorPropagatesCancellationFromDelegate() {
        com.helloai.core.agent.tool.ToolExecutor delegate = (name, args) -> {
            throw new AgentCancelledException("工具内部取消");
        };
        CancellableToolExecutor executor =
                new CancellableToolExecutor(delegate, CancellationToken.mutable(), 1000L);

        assertThatThrownBy(() -> executor.execute("t", "{}"))
                .isInstanceOf(AgentCancelledException.class)
                .hasMessage("工具内部取消");
    }
}
