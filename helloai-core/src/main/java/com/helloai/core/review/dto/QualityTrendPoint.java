package com.helloai.core.review.dto;

/**
 * 质量趋势点（Phase 5 质量度量看板）：按天分组的审查统计。
 *
 * <p>数据源 review_record（review 域），经 {@code ReviewService.statsTrendSource}
 * 提供；period 为 {@code YYYY-MM-DD} 文本（PostgreSQL date_trunc 格式化）。</p>
 *
 * @param period        统计日（YYYY-MM-DD）
 * @param reviewedCount 当日审查记录数
 * @param approvedCount 当日 APPROVED 记录数
 * @param avgScore      当日平均评分（1-5，无记录为 0）
 */
public record QualityTrendPoint(String period, long reviewedCount, long approvedCount, double avgScore) {
}
