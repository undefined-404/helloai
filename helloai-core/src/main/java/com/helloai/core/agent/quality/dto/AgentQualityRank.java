package com.helloai.core.agent.quality.dto;

/**
 * Agent 质量排行点（Phase 5 质量度量看板 agents 排行）。
 *
 * <p>排序口径：一次通过率降序 → 审查数降序 → agentId 升序（稳定）；
 * qualityScore 复用 {@code AgentQualityProfileService.computeQualityScore}
 * 同口径（SQL 只做排序筛选，分数由 Java 层逐行计算，保证口径唯一）。</p>
 *
 * @param agentId       执行者 Agent ID
 * @param agentName     执行者名称（经 AgentService 补名，缺失显示 ID 字符串）
 * @param reviewedCount 累计审查记录数
 * @param firstPassRate 一次通过率（0-100 整数百分比）
 * @param qualityScore  综合质量分（0-100；无评审数据为 null）
 */
public record AgentQualityRank(Long agentId, String agentName,
                               long reviewedCount, int firstPassRate,
                               Integer qualityScore) {
}
