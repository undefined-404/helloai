package com.helloai.core.review.dto;

/**
 * 抽检偏差统计（`RV-6`）：窗口内按原判结果分组的有效复判数与分歧数。
 *
 * <p>两个方向共用 {@code review_recheck_log}：{@code approved.divergent}
 * = 原判通过、复判驳回（放水）；{@code rejected.divergent} = 原判驳回、
 * 复判通过（误伤）。跳过（无 reviewer / 不可判定 / 快照漂移 / 无快照）
 * 不写日志，因此天然不计入分母。</p>
 *
 * @param approved 原判 {@code APPROVED} 的抽检桶
 * @param rejected 原判 {@code REJECTED} 的抽检桶
 */
public record RecheckStats(Bucket approved, Bucket rejected) {

    /**
     * 单侧统计桶。
     *
     * @param sampled   有效复判数（分母）
     * @param divergent 复判与原判不一致数（分子）
     * @param rate      比率百分比（0-100，两位小数；分母为 0 时为 0）
     */
    public record Bucket(long sampled, long divergent, double rate) {

        public static Bucket of(long sampled, long divergent) {
            double rate = sampled <= 0
                    ? 0D
                    : Math.round(divergent * 10_000D / sampled) / 100D;
            return new Bucket(sampled, divergent, rate);
        }
    }

    /** Mapper 聚合行（内部用）：{@code originalResult} 只可能是 APPROVED / REJECTED。 */
    public record Row(String originalResult, long sampled, long divergent) {
    }
}