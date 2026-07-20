package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.SubTask;
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

    /**
     * 查询 PENDING 孤儿子任务 ID 列表（v2.6 §4.1 2026-07-20 新增）。
     *
     * <p>场景：dispatch-mode=EVENT 主路径上 Spring 事务事件丢失、
     * {@code agent_execution_record} 行未被创建、但 {@code sub_task} 一直停在 PENDING。
     * 当前 التنفيذCommandPoller 扫的是 “有 execution_record 记录的孤儿”，
     * 覆盖不到“压根没建 record”的情况。本方法补上这个间隙。</p>
     *
     * <p>扫描条件：
     * <ul>
     *   <li>{@code status='PENDING'}</li>
     *   <li>{@code create_time < cutoff} （避免误伤刚创建的合法 PENDING）</li>
     *   <li>{@code deleted=0}</li>
     *   <li>{@code NOT EXISTS (SELECT 1 FROM agent_execution_record WHERE sub_task_id = st.id)}</li>
     * </ul>
     * </p>
     *
     * <p>只返回 id 列表（减少传输），调用方拿 id 后从 Service 补读最新状态后再重派，
     * 避免TOCTOU问题。</p>
     *
     * @param cutoff PENDING 阈值截止时间（create_time < cutoff 视为孤儿）
     * @param limit  单次返回最多条数
     * @return PENDING 孤儿子任务 ID 列表（按 id ASC）
     */
    List<Long> selectStalePendingWithoutExecutionRecord(@Param("cutoff") OffsetDateTime cutoff,
                                                        @Param("limit") int limit);
}
