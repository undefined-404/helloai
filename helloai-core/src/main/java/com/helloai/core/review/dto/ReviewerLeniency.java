package com.helloai.core.review.dto;

/**
 * Reviewer 放水率点（Phase 5 质量度量看板）：审查者维度通过率统计。
 *
 * <p>配 Phase 4 抽检度量放水（review_recheck_log 只管一致性，本统计按
 * review_record 的 APPROVED 占比反映宽松度）；approveRate 为 0-100 整数百分比。</p>
 *
 * @param reviewerAgentId 审查者 Agent ID
 * @param reviewerName    审查者名称（经 agent 域服务补名，缺失显示 ID 字符串）
 * @param reviewedCount   审查记录数
 * @param approveRate     APPROVED 占比（0-100）
 * @param avgScore        平均评分（1-5，无记录为 0）
 */
public record ReviewerLeniency(Long reviewerAgentId, String reviewerName,
                               long reviewedCount, int approveRate, double avgScore) {
}
