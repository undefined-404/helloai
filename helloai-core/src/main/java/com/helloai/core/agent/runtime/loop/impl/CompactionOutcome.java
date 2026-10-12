package com.helloai.core.agent.runtime.loop.impl;

/**
 * 一次上下文压缩的结果（REF-8.2.3，供循环记 {@code context_compacted} 审计事件）。
 *
 * @param trigger              触发路径：{@code pressure}（预算压力）| {@code overflow}（提供方报超窗后重试前）
 * @param prunedToolResults    被剪枝的工具结果消息条数（第一道，确定性）
 * @param shadowedMessages     被摘要取代的历史消息条数（第二道；未走摘要为 0）
 * @param estimatedTokensBefore 压缩前估算 token
 * @param estimatedTokensAfter  压缩后估算 token
 * @param summarized           是否真的走了 LLM 摘要
 */
public record CompactionOutcome(
        String trigger,
        int prunedToolResults,
        int shadowedMessages,
        int estimatedTokensBefore,
        int estimatedTokensAfter,
        boolean summarized) {

    /** 是否实际发生了改动（剪枝或摘要任一）。 */
    public boolean changed() {
        return prunedToolResults > 0 || shadowedMessages > 0;
    }
}
