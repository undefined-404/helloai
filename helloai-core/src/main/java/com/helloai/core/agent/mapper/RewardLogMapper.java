package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.RewardLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RewardLogMapper extends BaseMapper<RewardLog> {

    /** 物理删除某 Agent 的全部积分流水（绕过 @TableLogic，仅供 Agent 级联删除使用）。 */
    @Delete("DELETE FROM reward_log WHERE agent_id = #{agentId}")
    int physicalDeleteByAgentId(@Param("agentId") Long agentId);

    /**
     * 物理删除某任务下全部积分流水（cascade：{@code reward_log.sub_task_id} 语义列无外键）。
     *
     * <p>2026-10-05 D-1：任务级联删除时被静默漏删（实测 2 行孤儿）。须在 sub_task 行删除前执行。</p>
     */
    @Delete("DELETE FROM reward_log WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}
