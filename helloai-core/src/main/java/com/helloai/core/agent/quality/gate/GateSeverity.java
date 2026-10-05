package com.helloai.core.agent.quality.gate;

/**
 * 质量闸门问题级别（跨域统一词汇，语义与既有机械校验的 HARD / SOFT 一致）。
 *
 * <p>仅作级别标注，不改变任何判定的严重性口径。</p>
 */
public enum GateSeverity {

    /** 硬违规：可直接驳回。 */
    HARD,

    /** 软违规：落告警交下游（LLM）复核，不直接驳回。 */
    SOFT
}
