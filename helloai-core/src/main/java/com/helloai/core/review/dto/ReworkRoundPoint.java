package com.helloai.core.review.dto;

/**
 * 返工轮次分布点（Phase 5 质量度量看板）：按审查轮次分组计数。
 *
 * <p>round=N 表示子任务第 N 轮审查；前端展示时 round≥2 即返工轮次
 * （返工轮数 = round - 1）。数据源 review_record（review 域）。</p>
 *
 * @param round        审查轮次（≥1）
 * @param subTaskCount 该轮次审查记录数
 */
public record ReworkRoundPoint(Integer round, long subTaskCount) {
}
