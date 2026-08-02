package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.TaskExecutionRecordEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * TaskExecutionRecord Mapper（Phase B）。
 */
@Mapper
public interface TaskExecutionRecordMapper extends BaseMapper<TaskExecutionRecordEntity> {

    /** 按 taskId 查全部记录（用于构建 ContextSummary 与 Prompt 上下文）。 */
    @Select("SELECT * FROM task_execution_record WHERE task_id = #{taskId} AND deleted = 0 ORDER BY create_time ASC")
    List<TaskExecutionRecordEntity> selectByTaskId(@Param("taskId") Long taskId);

    /** 按 (taskId, subTaskId) 查唯一记录（rework 时存在则覆盖）。 */
    @Select("SELECT * FROM task_execution_record WHERE task_id = #{taskId} AND sub_task_id = #{subTaskId} AND deleted = 0 LIMIT 1")
    TaskExecutionRecordEntity selectByTaskIdAndSubTaskId(@Param("taskId") Long taskId, @Param("subTaskId") Long subTaskId);

    /** 物理删除某任务的全部记录（任务级联删除时使用）。 */
    @Delete("DELETE FROM task_execution_record WHERE task_id = #{taskId}")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);

    /** 物理删除某子任务的旧记录（rework 覆盖语义）。 */
    @Delete("DELETE FROM task_execution_record WHERE task_id = #{taskId} AND sub_task_id = #{subTaskId}")
    int physicalDeleteByTaskIdAndSubTaskId(@Param("taskId") Long taskId, @Param("subTaskId") Long subTaskId);
}