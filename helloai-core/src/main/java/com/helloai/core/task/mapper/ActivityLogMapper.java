package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.ActivityLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivityLogMapper extends BaseMapper<ActivityLog> {

    /** 物理删除某 Agent 的全部活动日志（绕过 @TableLogic，仅供 Agent 级联删除使用）。 */
    @Delete("DELETE FROM activity_log WHERE agent_id = #{agentId}")
    int physicalDeleteByAgentId(@Param("agentId") Long agentId);
}
