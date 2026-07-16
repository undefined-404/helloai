package com.helloai.api.dto.duty;

import lombok.Data;

/**
 * 值班租约状态概览（AgentHub V1 P1 值班报表看板卡片数据源）。
 *
 * <p>各状态计数由 {@code AgentDutyLeaseService.countByStatus()} 聚合而来，
 * {@code activeCount} 即当前在岗 Agent 数（一个 Agent 同时最多一条 ACTIVE 租约）。</p>
 */
@Data
public class DutyOverviewResponse {

    /** 当前值班中（ACTIVE）租约条数，等于在岗 Agent 数。 */
    private long activeCount;

    /** 已签退（CLOSED）租约条数。 */
    private long closedCount;

    /** 已过期（EXPIRED）租约条数。 */
    private long expiredCount;

    /** 全部租约条数（历史累计，未删除）。 */
    private long totalCount;
}
