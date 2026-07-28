package com.helloai.api.dto.duty;

import lombok.Data;

/**
 * 今日打卡概览（看板卡片数据源）。
 *
 * <p>各状态计数由 {@code AgentDutyLeaseService.countTodayAgentsByStatus()} 聚合而来，
 * 按 Agent 维度去重：每个 Agent 只按其最新租约状态计一次
 * （要么在线、要么下班、要么超时），不再按历史租约条数累计。</p>
 */
@Data
public class DutyOverviewResponse {

    /** 今日在线（最新租约 ACTIVE）的 Agent 数。 */
    private long activeCount;

    /** 今日已下班（最新租约 CLOSED）的 Agent 数。 */
    private long closedCount;

    /** 今日超时（最新租约 EXPIRED）的 Agent 数。 */
    private long expiredCount;
}
