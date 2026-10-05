package com.helloai.core.task.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.workflow.entity.WorkflowInstance;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * WorkflowInstance Mapper（N-001，C1-S2）。
 *
 * <p>2026-10-05 D-1：{@code workflow_instance.task_id} 语义列无外键，任务级联删除时
 * 会被静默漏删；补物理删除入口专供级联删除使用。</p>
 */
@Mapper
public interface WorkflowInstanceMapper extends BaseMapper<WorkflowInstance> {

    /** 物理删除某任务下全部工作流实例（cascade：task_id 语义列无外键）。 */
    @Delete("DELETE FROM workflow_instance WHERE task_id = #{taskId}")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}
