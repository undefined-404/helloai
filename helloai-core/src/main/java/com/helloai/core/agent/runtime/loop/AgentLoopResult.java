package com.helloai.core.agent.runtime.loop;

/**
 * Agent 循环结果（P0-C）。
 *
 * <p>由 {@link AgentLoop} 返回的不可变结果。success=true 时 errorMessage 恒 null；
 * {@code finishReason} 取 {@code STOP} / {@code MAX_ITERATIONS} / {@code ERROR} 三值之一。</p>
 *
 * <p>{@code tokenUsage} 为全部轮次 totalTokens 的累加值（B3 tokenUsage 采集）；
 * provider 未返回 usage 时恒 null（best-effort，不阻断循环）。</p>
 *
 * @param success       是否成功（达到终态文本 = true；maxIterations 耗尽 / 异常 = false）
 * @param text          终态正文（可为空串；MAX_ITERATIONS 时为末轮正文）
 * @param thinking      推理模型思考过程（无则 null）
 * @param iterations    实际 LLM 调用轮数
 * @param toolCallCount 实际工具执行次数
 * @param tokenUsage    全部轮次 Token 用量累加（null = provider 未返回 usage）
 * @param finishReason  结束原因（"STOP" / "MAX_ITERATIONS" / "ERROR"）
 * @param errorMessage  失败原因（成功时为 null）
 */
public record AgentLoopResult(boolean success, String text, String thinking, int iterations,
                              int toolCallCount, Integer tokenUsage, String finishReason, String errorMessage) {

    /** 模型输出终态文本（无更多工具调用）正常结束。 */
    public static AgentLoopResult stop(String text, String thinking, int iterations, int toolCallCount) {
        return stop(text, thinking, iterations, toolCallCount, null);
    }

    /** 模型输出终态文本（带全部轮次 Token 用量累加值）。 */
    public static AgentLoopResult stop(String text, String thinking, int iterations, int toolCallCount,
                                       Integer tokenUsage) {
        return new AgentLoopResult(true, text, thinking, iterations, toolCallCount, tokenUsage, "STOP", null);
    }

    /** 达到 maxIterations 硬上限仍未终态（防死循环），携带末轮正文。 */
    public static AgentLoopResult maxIterations(String text, int iterations, int toolCallCount) {
        return maxIterations(text, iterations, toolCallCount, null);
    }

    /** 达到 maxIterations 硬上限仍未终态（带已发生轮次的 Token 用量累加值）。 */
    public static AgentLoopResult maxIterations(String text, int iterations, int toolCallCount,
                                                Integer tokenUsage) {
        return new AgentLoopResult(false, text, null, iterations, toolCallCount, tokenUsage,
                "MAX_ITERATIONS", "agent loop reached max iterations");
    }

    /** 入参缺失 / 模型异常返回（best-effort 不抛）。 */
    public static AgentLoopResult error(String errorMessage, int iterations, int toolCallCount) {
        return error(errorMessage, iterations, toolCallCount, null);
    }

    /** 入参缺失 / 模型异常返回（带已发生轮次的 Token 用量累加值）。 */
    public static AgentLoopResult error(String errorMessage, int iterations, int toolCallCount,
                                        Integer tokenUsage) {
        return new AgentLoopResult(false, "", null, iterations, toolCallCount, tokenUsage, "ERROR", errorMessage);
    }
}
