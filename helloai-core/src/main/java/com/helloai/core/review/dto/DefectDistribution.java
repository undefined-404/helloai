package com.helloai.core.review.dto;

/**
 * 驳回原因分布点（Phase 5 质量度量看板）：issues 四元组 {@code [defect]} 标签计数。
 *
 * <p>解析口径与质量画像增量/rebuild 完全一致（复用 agent 域
 * {@code DefectLabelParser}，review → agent 合法向下依赖），保证看板
 * 与画像表 issue_defect_stats 对账一致。</p>
 *
 * @param defectTag 缺陷标签（如 defect:spec 缺失）
 * @param count     窗口内出现次数
 */
public record DefectDistribution(String defectTag, long count) {
}
