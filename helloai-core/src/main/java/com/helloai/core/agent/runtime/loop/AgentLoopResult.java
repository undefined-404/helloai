package com.helloai.core.agent.runtime.loop;

/**
 * Agent 循环结果（P0-C Phase 3）。
 *
 * <p>由 {@link AgentLoop} 返回的不可变结果。success=true 时 errorMessage 恒 null；
 * {@code finishReason} 取 {@code STOP} / {@code MAX_ITERATIONS} / {@code ERROR} 三值之一。</p>
 *
 * @param success       是否成功（达到终态文本 = true；maxIterations 耗尽 / 异常 = false）
 * @param text          终态正文（可为空串；MAX_ITERATIONS 时为末轮正文）
 * @param thinking      推理模型思考过程（无则 null）
 * @param iterations    实际 LLM 调用轮数
 * @param toolCallCount 实际工具执行次数
 * @param finishReason  结束原因（"STOP" / "MAX_ITERATIONS" / "ERROR"）
 * @param errorMessage  失败原因（成功时为 null）
 */
public record AgentLoopResult(boolean success, String text, String thinking, int iterations,
                              int toolCallCount, String finishReason, String errorMessage) {

    /** 模型输出终态文本（无更多工具调用）正常结束。 */
    public static AgentLoopResult stop(String text, String thinking, int iterations, int toolCallCount) {
        return new AgentLoopResult(true, text, thinking, iterations, toolCallCount, "STOP", null);
    }

    /** 达到 maxIterations 硬上限仍未终态（防死循环），携带末轮正文。 */
    public static AgentLoopResult maxIterations(String text, int iterations, int toolCallCount) {
        return new AgentLoopResult(false, text, null, iterations, toolCallCount,
                "MAX_ITERATIONS", "agent loop reached max iterations");
    }

    /** 入参缺失 / 模型异常返回（best-effort 不抛）。 */
    public static AgentLoopResult error(String errorMessage, int iterations, int toolCallCount) {
        return new AgentLoopResult(false, "", null, iterations, toolCallCount, "ERROR", errorMessage);
    }
}
