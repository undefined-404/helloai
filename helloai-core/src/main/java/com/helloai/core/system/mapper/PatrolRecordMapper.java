package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.entity.PatrolRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PatrolRecordMapper extends BaseMapper<PatrolRecord> {

    /** 物理删除某 Agent 的全部巡查记录（绕过 @TableLogic，仅供 Agent 级联删除使用）。 */
    @Delete("DELETE FROM patrol_record WHERE patrol_agent_id = #{agentId}")
    int physicalDeleteByAgentId(@Param("agentId") Long agentId);

    /** 物理删除某任务下全部巡查记录（外键引用 sub_task.id，必须先于子任务删除，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM patrol_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}
