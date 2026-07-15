package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.core.entity.AgentDutyLease;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent 值班租约 Mapper。
 *
 * <p>AgentHub V1 T3 最小骨架。</p>
 */
@Mapper
public interface AgentDutyLeaseMapper extends BaseMapper<AgentDutyLease> {

    /**
     * 查询 Agent 当前有效的值班租约。
     *
     * <p>条件：agentId 匹配 + status=ACTIVE + 未删除，
     * 按 create_time 倒序取最新一条。</p>
     */
    AgentDutyLease selectActiveByAgentId(@Param("agentId") Long agentId);

    /**
     * 关闭 Agent 当前所有有效租约。
     *
     * <p>将指定 agent 的所有 ACTIVE 租约改为目标状态并写入关闭原因。
     * 不限定条数：正常情况下只有一条，但防御性处理残留。</p>
     *
     * @return 影响行数
     */
    int closeActiveLeases(@Param("agentId") Long agentId,
                          @Param("newStatus") String newStatus,
                          @Param("closeReason") String closeReason,
                          @Param("now") OffsetDateTime now);

    /**
     * 扫描超时未续约的 ACTIVE 租约。
     *
     * <p>条件：status=ACTIVE + expires_at &lt; cutoff + 未删除。</p>
     */
    List<AgentDutyLease> selectExpiredLeases(@Param("cutoff") OffsetDateTime cutoff,
                                             @Param("limit") int limit);
}
