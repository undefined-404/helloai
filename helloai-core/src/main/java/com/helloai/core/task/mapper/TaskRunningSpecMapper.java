package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.TaskRunningSpecEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * TaskRunningSpec Mapper（Phase B）。
 */
@Mapper
public interface TaskRunningSpecMapper extends BaseMapper<TaskRunningSpecEntity> {

    /** 按 taskId 查询（运行时常按 1:1 用 BaseMapper.getOne 即可，这里留冗余声明）。 */
    @Select("SELECT * FROM task_running_spec WHERE task_id = #{taskId} AND deleted = 0 LIMIT 1")
    TaskRunningSpecEntity selectByTaskId(@Param("taskId") Long taskId);

    /** 重写 ContextSummary（不改变 baseline / version）。 */
    @Update("UPDATE task_running_spec SET context_summary = #{contextSummary}, update_time = CURRENT_TIMESTAMP WHERE task_id = #{taskId} AND deleted = 0")
    int updateContextSummary(@Param("taskId") Long taskId, @Param("contextSummary") String contextSummary);

    /** 物理删除某任务的 Spec（任务级联删除时使用）。 */
    @Delete("DELETE FROM task_running_spec WHERE task_id = #{taskId}")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}