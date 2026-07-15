package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.entity.SubTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface SubTaskMapper extends BaseMapper<SubTask> {

    /**
     * 原子认领子任务：仅当 status='PENDING' 且 (assigned_agent IS NULL OR assigned_agent = agentId) 时才更新。
     * 返回 affected rows（1=认领成功，0=竞争失败）。
     */
    int claimAtomic(@Param("subTaskId") Long subTaskId, @Param("agentId") Long agentId);

    // ══════════════════════════════════════════════════════════════
    //  N11 阈值回退：外部回退次数 + 仍属于某 Agent 的在跑子任务
    //  详见 SubTaskMapper.xml 对应 SQL 注释
    // ══════════════════════════════════════════════════════════════

    /**
     * 原子累加 sub_task.external_fallback_count。
     *
     * @return 1 = 成功累加；0 = 子任务不存在或已删除
     */
    int incrementExternalFallbackCount(@Param("subTaskId") Long subTaskId,
                                       @Param("now") OffsetDateTime now);

    /**
     * 查询某 Agent 处于 IN_PROGRESS/ASSIGNED/REWORK 的子任务列表（按 id 升序，limit 上限）。
     */
    List<SubTask> selectInFlightByAgent(@Param("agentId") Long agentId,
                                        @Param("limit") int limit);

    /**
     * 查询 ASSIGNED 超时未 claim 的子任务（按 update_time 升序，limit 上限）。
     *
     * <p>只查 status=ASSIGNED 且 update_time 早于 deadline 的记录。
     * 用于 AssignedSubTaskTimeoutTask 巡检回收。</p>
     *
     * @param deadline 超时截止时间（update_time < deadline 视为超时）
     * @param limit    单次最多返回条数
     */
    List<SubTask> selectTimedOutAssigned(@Param("deadline") OffsetDateTime deadline,
                                         @Param("limit") int limit);
}
