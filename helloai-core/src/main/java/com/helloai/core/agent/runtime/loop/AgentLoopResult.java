package com.helloai.core.agent.runtime.loop;

/**
 * Agent 循环结果（P0-C）。
 *
 * <p>由 {@link AgentLoop} 返回的不可变结果。success=true 时 errorMessage 恒 null；
 * {@code finishReason} 取 {@code STOP} / {@code MAX_ITERATIONS} / {@code MAX_TOKENS} / {@code ERROR} 四值之一。</p>
 *
 * <p>{@code tokenUsage} 为全部轮次 totalTokens 的累加值（B3 tokenUsage 采集）；
 * provider 未返回 usage 时恒 null（best-effort，不阻断循环）。</p>
 *
 * <p><b>★{@code MAX_TOKENS} 是独立终态（REF-8.7，对齐 DSH）</b>：模型输出因 token 上限被截断时
 * <b>不得静默当成功</b>——故这里给一个与 {@code STOP} 并列的终态，且带 <b>sticky</b> 语义
 * （任一步触顶 ⇒ 本 turn 终态即为此，后续正常步骤不得下调）。判据与装配见
 * {@code ChatModelToolLoop} 与 {@link #maxTokens}。</p>
 *
 * @param success       是否成功（达到终态文本 = true；maxIterations 耗尽 / 截断 / 异常 = false）
 * @param text          终态正文（可为空串；MAX_ITERATIONS / MAX_TOKENS 时为末轮正文）
 * @param thinking      推理模型思考过程（无则 null）
 * @param iterations    实际 LLM 调用轮数
 * @param toolCallCount 实际工具执行次数
 * @param tokenUsage    全部轮次 Token 用量累加（null = provider 未返回 usage）——保留供既有消费方零改动
 * @param finishReason  结束原因（"STOP" / "MAX_ITERATIONS" / "MAX_TOKENS" / "ERROR"）
 * @param errorMessage  失败原因（成功时为 null）
 * @param usage         本轮用量（REF-8.8：互斥分量 + 耗时）；未经循环装配（如单测直接造的草稿）时为 null
 */
public record AgentLoopResult(boolean success, String text, String thinking, int iterations,
                              int toolCallCount, Integer tokenUsage, String finishReason, String errorMessage,
                              AgentUsage usage) {

    /** 模型输出终态文本（无更多工具调用）正常结束。 */
    public static AgentLoopResult stop(String text, String thinking, int iterations, int toolCallCount) {
        return stop(text, thinking, iterations, toolCallCount, null);
    }

    /** 模型输出终态文本（带全部轮次 Token 用量累加值）。 */
    public static AgentLoopResult stop(String text, String thinking, int iterations, int toolCallCount,
                                       Integer tokenUsage) {
        return new AgentLoopResult(true, text, thinking, iterations, toolCallCount, tokenUsage, "STOP", null, null);
    }

    /** 达到 maxIterations 硬上限仍未终态（防死循环），携带末轮正文。 */
    public static AgentLoopResult maxIterations(String text, int iterations, int toolCallCount) {
        return maxIterations(text, iterations, toolCallCount, null);
    }

    /** 达到 maxIterations 硬上限仍未终态（带已发生轮次的 Token 用量累加值）。 */
    public static AgentLoopResult maxIterations(String text, int iterations, int toolCallCount,
                                                Integer tokenUsage) {
        return new AgentLoopResult(false, text, null, iterations, toolCallCount, tokenUsage,
                "MAX_ITERATIONS", "agent loop reached max iterations", null);
    }

    /**
     * 模型输出因 token 上限被**截断**（REF-8.7，对齐 DSH `max-tokens` 独立终态）——
     * **不得静默当成功**：{@code success=false} + {@code finishReason="MAX_TOKENS"}，
     * {@code text} 保留已产出的部分正文（证据不丢）。
     *
     * <p><b>sticky 语义</b>（DSH `agent.ts:338-343` 注释明写「max-tokens is sticky」）：只要
     * **任一步**触顶，本 turn 终态即为此，后续正常步骤**不得**下调（由 {@code ChatModelToolLoop}
     * 的 sticky 标志在该 turn 全部出口统一保证——含挂点改写之后）。</p>
     */
    public static AgentLoopResult maxTokens(String text, String thinking, int iterations, int toolCallCount) {
        return maxTokens(text, thinking, iterations, toolCallCount, null);
    }

    /** 截断终态（带已发生轮次的 Token 用量累加值，口径同 {@link #stop}）。 */
    public static AgentLoopResult maxTokens(String text, String thinking, int iterations, int toolCallCount,
                                            Integer tokenUsage) {
        return new AgentLoopResult(false, text, thinking, iterations, toolCallCount, tokenUsage,
                "MAX_TOKENS", "agent loop output truncated by max tokens", null);
    }

    /** 入参缺失 / 模型异常返回（best-effort 不抛）。 */
    public static AgentLoopResult error(String errorMessage, int iterations, int toolCallCount) {
        return error(errorMessage, iterations, toolCallCount, null);
    }

    /** 入参缺失 / 模型异常返回（带已发生轮次的 Token 用量累加值）。 */
    public static AgentLoopResult error(String errorMessage, int iterations, int toolCallCount,
                                        Integer tokenUsage) {
        return new AgentLoopResult(false, "", null, iterations, toolCallCount, tokenUsage, "ERROR", errorMessage, null);
    }

    /**
     * 附上本轮用量（REF-8.8）；其余字段（含终态与正文）不变。
     *
     * <p>由 {@code ChatModelToolLoop} 在 <b>挂点改写之后</b>统一装配 —— 使「用量」与
     * 「终态」彼此正交：挂点可改终态 / 正文（如收尾轮），但不会把用量丢掉。</p>
     */
    public AgentLoopResult withUsage(AgentUsage usage) {
        return new AgentLoopResult(success, text, thinking, iterations, toolCallCount, tokenUsage,
                finishReason, errorMessage, usage);
    }

    /**
     * 「尽力交付」判据（`MA-10` 改动 1，2026-11-20）：
     * <b>轮次预算用尽、但手里有可交付正文</b> ⇒ 消费侧应判成功，而不是把整个任务判失败。
     *
     * <p><b>为什么本判据放在这里而不是直接改 {@link #maxIterations} 的 {@code success}</b>：
     * {@code success} 被多处同时消费（{@code FinalReportFenceHook} 的收尾轮判据、
     * {@code BusinessSurfaceLoopRunner} 的映射、{@code RuntimeTurnExecutor} 的状态映射、
     * 以及锁定该语义的既有单测）。把 {@code success} 改成 {@code true} 会一次性改变
     * 全部读取方的假设，且「循环层是否自然终止」与「业务层算不算交付成功」是<b>两件事</b>——
     * 前者为 {@code false} 是真值（确实没走到自然终态），不该被掩盖（判据：真值不掩盖）。</p>
     *
     * <p><b>为什么空正文退回失败</b>：空正文的「轮次耗尽」确实是失败，必须继续走
     * {@code agent_runtime_empty_output} 一类既有护栏；把空正文也判成功等于把失败伪装成交付
     * （蓝图 §4 `MA-10` ⑦ 第一条风险）。</p>
     *
     * <p><b>消费侧口径</b>：命中本判据时，调用方应判 {@code SUCCESS} + 保留
     * {@code finishReason="MAX_ITERATIONS"}（让下游能区分「自然 STOP」与「预算用尽」），
     * 并<b>豁免</b>按自然 STOP 施加的输出契约（VERIFICATION 围栏）——尽力交付不适用
     * 「自证交付」的 fail-close 口径，那是给自然终态用的。</p>
     */
    public boolean isPartialDelivery() {
        return !success
                && "MAX_ITERATIONS".equals(finishReason)
                && text != null
                && !text.isBlank();
    }
}
