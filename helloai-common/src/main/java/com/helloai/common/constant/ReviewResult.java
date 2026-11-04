package com.helloai.common.constant;

/**
 * 审查结论（三态，`RV-2`）。
 *
 * <p>{@link #INCONCLUSIVE} = 判定本身不可采信（如「驳回」却给满分），既不是通过也不是驳回：
 * 落库仅作审计，不计入通过率 / 平均分分母，不触发返工，子任务停留 REVIEW 等人工。</p>
 */
public enum ReviewResult {
    APPROVED,
    REJECTED,
    INCONCLUSIVE
}
