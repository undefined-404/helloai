package com.helloai.core.agent.runtime.loop;

/**
 * 一次循环的 Token 用量与耗时（REF-8.8，对齐 DSH `packages/llm/llm/src/types.ts:175-188`）。
 *
 * <p><b>为什么需要它</b>：改造前用量只有单一 {@code totalTokens}（B3 采集），而参照实现的
 * usage 是 <b>互斥分量</b>（DSH 源码注释原文：<i>Counts are DISJOINT</i>）—— 只记总数看不出
 * 「开销花在输入还是输出、有没有命中提示缓存、推理开销占多少」，成本画像与优化判断都缺依据。</p>
 *
 * <p><b>降级纪律（对齐 §10.8 红线③「provider 能力差异一律降级、不报错」）</b>：缺哪个分量，
 * 该字段即为 {@code null}（{@code totalTokens} 亦可能为 null），<b>不</b>因本判据新增拒绝面。
 * 其中输入 / 输出分量可经 spring-ai 的通用 {@code Usage#getPromptTokens/CompletionTokens}
 * 稳定取到；<b>缓存读 / 缓存写 / 推理</b>属 provider 私有（只存在于 {@code nativeUsage} 内），
 * 仅当能结构化读到才填，否则留 null。</p>
 *
 * @param inputTokens      输入（prompt）token 累计；provider 未给 ⇒ null
 * @param outputTokens     输出（completion）token 累计；provider 未给 ⇒ null
 * @param cacheReadTokens  缓存读 token 累计（provider 私有）；未给 ⇒ null
 * @param cacheWriteTokens 缓存写 token 累计（provider 私有）；未给 ⇒ null
 * @param reasoningTokens  推理 token 累计（provider 私有）；未给 ⇒ null
 * @param totalTokens      总 token 累计（与 {@link AgentLoopResult#tokenUsage()} 同源）；未给 ⇒ null
 * @param latencyMillis    本轮循环墙钟耗时（毫秒，含挂点如收尾轮的耗时）
 */
public record AgentUsage(Integer inputTokens, Integer outputTokens,
                         Integer cacheReadTokens, Integer cacheWriteTokens,
                         Integer reasoningTokens, Integer totalTokens,
                         long latencyMillis) {

    /** 空用量（各分量 null、耗时 0）。 */
    public static AgentUsage empty() {
        return new AgentUsage(null, null, null, null, null, null, 0L);
    }

    /** 是否未采集到任何用量分量（全 null）。 */
    public boolean isEmpty() {
        return inputTokens == null && outputTokens == null && cacheReadTokens == null
                && cacheWriteTokens == null && reasoningTokens == null && totalTokens == null;
    }

    /**
     * 累加一轮用量（null 安全：某侧为 null 则保持另一侧；两侧皆 null 仍为 null）。
     * <b>不</b>累加 {@link #latencyMillis}（由 {@link #withLatency} 单独置入）。
     */
    public AgentUsage plus(Integer input, Integer output, Integer total,
                           Integer cacheRead, Integer cacheWrite, Integer reasoning) {
        return new AgentUsage(add(inputTokens, input), add(outputTokens, output),
                add(cacheReadTokens, cacheRead), add(cacheWriteTokens, cacheWrite),
                add(reasoningTokens, reasoning), add(totalTokens, total), latencyMillis);
    }

    /** 置入本轮墙钟耗时（毫秒），其余分量不变。 */
    public AgentUsage withLatency(long millis) {
        return new AgentUsage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens,
                reasoningTokens, totalTokens, millis);
    }

    private static Integer add(Integer accumulated, Integer delta) {
        if (delta == null) {
            return accumulated;
        }
        return (accumulated != null ? accumulated : 0) + delta;
    }
}
