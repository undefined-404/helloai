package com.helloai.core.agent.runtime.loop;

import java.util.List;

/**
 * 循环进度检查点（P0-C checkpoint：每轮 iteration 边界落一次）。
 *
 * <p>记录「执行到哪」的进度事实：已完成循环轮数 / 工具调用次数 / 消息序列深度 /
 * 已执行工具名；供中断恢复时注入接续上下文（{@code AgentSessionService.saveLoopCheckpoint}
 * merge 进 {@code snapshot.loop}）。保持 V66 边界：不做 LLM 级断点续接——
 * 只落进度事实，不回放消息历史。</p>
 *
 * @param iteration         已完成的循环轮序（从 1 起，与 LLM 调用轮数一致）
 * @param toolCallCount     累计工具执行次数（含失败调用）
 * @param messageCount      当前对话消息序列条数（System + User + Assistant + ToolResponse）
 * @param executedToolNames 已执行工具名（按首次执行顺序去重；null 视为空）
 */
public record LoopCheckpoint(int iteration, int toolCallCount, int messageCount,
                             List<String> executedToolNames) {

    public LoopCheckpoint {
        executedToolNames = executedToolNames == null ? List.of() : List.copyOf(executedToolNames);
    }
}
