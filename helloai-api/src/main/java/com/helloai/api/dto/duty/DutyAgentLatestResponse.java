package com.helloai.api.dto.duty;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 维度值班列表项（每个 Agent 一行）。
 *
 * <p>在最新一条租约视图（{@link DutyLeaseResponse}）基础上冗余
 * {@code leaseCount}，供"点更多查看该 Agent 全部租约"入口展示总数。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DutyAgentLatestResponse extends DutyLeaseResponse {

    /** 该 Agent 的租约总条数（未删除口径）。 */
    private Long leaseCount;
}
