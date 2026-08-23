package com.helloai.core.agent.quality.dto;

/**
 * 全局质量概览（Phase 5 质量度量看板 overview 卡片）。
 *
 * <p>数据源 agent_quality_profile（agent 域画像表，经 AgentQualityProfileService
 * 收口）；窗口语义 = 画像表存量（执行者维度累计值），非时间窗口。</p>
 *
 * @param totalReviewed   画像表累计审查记录数（执行者维度总和）
 * @param totalApproved   累计通过记录数
 * @param firstPassRate   累计一次通过率（0-100 整数百分比；无首轮数据为 0）
 * @param avgReworkRounds 平均返工轮数（rework_round_sum 均值，保留小数）
 * @param activeExecutors 活跃执行者画像行数
 */
public record QualityOverview(long totalReviewed, long totalApproved,
                              int firstPassRate, double avgReworkRounds,
                              int activeExecutors) {
}
