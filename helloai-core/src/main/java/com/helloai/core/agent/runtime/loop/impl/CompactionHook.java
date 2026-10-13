package com.helloai.core.agent.runtime.loop.impl;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * 默认压缩挂点（REF-8.4 切片 1）：把 {@link ChatModelToolLoop} 原有的两处内联压缩
 * （请求前 {@code compactIfNeeded} / 请求异常 {@code compactOnOverflow}）搬入具名挂点，
 * 使压缩成为 hook 的默认实现而不再内联在循环体里。
 *
 * <p>行为与改造前逐字一致：本 hook 只是把对 {@link ContextCompactor} 的调用从循环体
 * 挪进 {@link #beforeRequest} / {@link #onRequestError}；循环仍自持预算/重试上限（
 * {@code ContextCompactor#maxSummarizationsPerRun} / {@code maxOverflowRetries}），
 * 摘要预算与溢出计数不回退到 hook 内。{@code no-op} 压缩器（{@code ContextCompactor#noop}）
 * 恒返回 {@code null} ⇒ 与本 hook 组合后行为与「无压缩」一致。</p>
 */
final class CompactionHook implements LoopHook {

    private final ContextCompactor compactor;

    CompactionHook(ContextCompactor compactor) {
        this.compactor = compactor;
    }

    @Override
    public CompactionOutcome beforeRequest(List<Message> messages, ChatModel chatModel,
                                           ChatOptions options, int summarizationBudget) {
        return compactor == null ? null
                : compactor.compactIfNeeded(messages, chatModel, options, summarizationBudget);
    }

    @Override
    public CompactionOutcome onRequestError(List<Message> messages, ChatModel chatModel,
                                            ChatOptions options, int summarizationBudget) {
        return compactor == null ? null
                : compactor.compactOnOverflow(messages, chatModel, options, summarizationBudget);
    }
}