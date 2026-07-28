package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.common.constant.AgentDutyLeaseStatus;
import com.helloai.core.agent.entity.AgentDutyLease;
import com.helloai.core.agent.entity.AgentDutyLeaseLatestRow;
import org.apache.ibatis.annotations.Delete;
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

    /**
     * Agent 维度分页：每个 Agent 取最新一条租约 + 该 Agent 租约总数。
     *
     * <p>PostgreSQL {@code DISTINCT ON} 按 start_time 倒序取组内最新，
     * 外层按最新租约开始时间倒序（最近上班的 Agent 在前）。</p>
     */
    List<AgentDutyLeaseLatestRow> selectLatestPerAgent(@Param("offset") long offset,
                                                       @Param("size") long size);

    /** 有租约记录的 Agent 总数（Agent 维度分页的 total）。 */
    long countDistinctAgents();

    /** 物理删除某 Agent 的全部值班租约（外键引用 agent.id，仅供 Agent 级联删除使用）。 */
    @Delete("DELETE FROM agent_duty_lease WHERE agent_id = #{agentId}")
    int physicalDeleteByAgentId(@Param("agentId") Long agentId);
}
