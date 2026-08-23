package com.helloai.core.review.dto;

import com.helloai.core.agent.quality.dto.QualityOverview;

import java.util.List;

/**
 * 质量看板聚合响应（Phase 5：GET /api/admin/quality/dashboard?days=）。
 *
 * <p>由 review 域 {@code QualityDashboardService} 组装（聚合放 Service 而非
 * Controller，遵守零编排红线）：overview 来自 agent 域画像聚合，其余四组
 * 来自 review_record 窗口统计（review → agent 合法向下依赖）。</p>
 *
 * @param overview           全局概览（画像表存量）
 * @param trends             按天质量趋势（窗口内，升序）
 * @param defectDistributions 驳回原因分布（[defect] 标签计数降序）
 * @param reworkRounds       返工轮次分布（round 升序）
 * @param reviewers          Reviewer 放水率（审查数降序）
 */
public record QualityDashboardResponse(
        QualityOverview overview,
        List<QualityTrendPoint> trends,
        List<DefectDistribution> defectDistributions,
        List<ReworkRoundPoint> reworkRounds,
        List<ReviewerLeniency> reviewers) {
}
