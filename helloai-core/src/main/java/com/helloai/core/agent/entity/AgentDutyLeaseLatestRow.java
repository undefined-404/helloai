package com.helloai.core.agent.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 值班租约"按 Agent 分组取最新一条"的查询视图行。
 *
 * <p>非表实体：在租约全字段基础上冗余 {@code leaseCount}
 * （该 Agent 的租约总条数），供 Agent 维度值班列表一次查询直出。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AgentDutyLeaseLatestRow extends AgentDutyLease {

    /** 该 Agent 的租约总条数（未删除口径）。 */
    private Long leaseCount;
}
