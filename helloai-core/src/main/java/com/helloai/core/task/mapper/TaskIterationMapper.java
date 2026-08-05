package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.TaskIteration;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * TaskIteration Mapper。
 *
 * <p>继承 BaseMapper 获得标准 CRUD；额外提供按 task_id 批量删除用于回填幂等。</p>
 */
public interface TaskIterationMapper extends BaseMapper<TaskIteration> {

    /**
     * 按顶层任务 ID 删除全部迭代记录（回填前幂等清理）。
     *
     * @param taskId 顶层任务 ID
     * @return 删除行数
     */
    @Delete("DELETE FROM task_iteration WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") Long taskId);

    /**
     * 查找有 DONE 子任务但尚未在 task_iteration 中回填过的历史任务 ID 列表。
     *
     * @return 待回填的任务 ID 列表（可能为空）
     */
    @Select("SELECT DISTINCT st.task_id FROM sub_task st WHERE st.status = 'DONE' AND st.task_id NOT IN (SELECT DISTINCT task_id FROM task_iteration)")
    List<Long> findBackfillCandidateTaskIds();
}
