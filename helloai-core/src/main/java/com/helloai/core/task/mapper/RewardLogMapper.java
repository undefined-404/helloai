package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.RewardLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RewardLogMapper extends BaseMapper<RewardLog> {

    /** 物理删除某 Agent 的全部积分流水（绕过 @TableLogic，仅供 Agent 级联删除使用）。 */
    @Delete("DELETE FROM reward_log WHERE agent_id = #{agentId}")
    int physicalDeleteByAgentId(@Param("agentId") Long agentId);
}
