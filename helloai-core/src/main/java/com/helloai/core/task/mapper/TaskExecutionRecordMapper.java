package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import com.helloai.core.task.entity.TaskExecutionRecordEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * TaskExecutionRecord Mapper（Phase B）。
 */
@Mapper
public interface TaskExecutionRecordMapper extends BaseMapper<TaskExecutionRecordEntity> {

    /**
     * 按 taskId 查全部记录（用于构建 ContextSummary 与 Prompt 上下文）。
     *
     * <p><b>JSONB 列必须显式配 typeHandler</b>：注解 SQL 走普通自动映射，不套用
     * {@code @TableField(typeHandler)}；不配则 {@code keyDecisions} / {@code downstreamNotes} /
     * {@code deliverables} 三个列表静默为 null，{@code compileContextSummary} 会永久丢掉
     * 「关键决策 / 下游须知 / 产出文件」（2026-11-04 与 TaskRunningSpecMapper 同批修复）。</p>
     */
    @Select("SELECT * FROM task_execution_record WHERE task_id = #{taskId} AND deleted = 0 ORDER BY create_time ASC")
    @Results(id = "executionRecordResult", value = {
            @Result(column = "key_decisions", property = "keyDecisions", typeHandler = PgJsonbTypeHandler.class),
            @Result(column = "downstream_notes", property = "downstreamNotes", typeHandler = PgJsonbTypeHandler.class),
            @Result(column = "deliverables", property = "deliverables", typeHandler = PgJsonbTypeHandler.class)
    })
    List<TaskExecutionRecordEntity> selectByTaskId(@Param("taskId") Long taskId);

    /** 按 (taskId, subTaskId) 查唯一记录（rework 时存在则覆盖）。JSONB 映射同上。 */
    @Select("SELECT * FROM task_execution_record WHERE task_id = #{taskId} AND sub_task_id = #{subTaskId} AND deleted = 0 LIMIT 1")
    @ResultMap("executionRecordResult")
    TaskExecutionRecordEntity selectByTaskIdAndSubTaskId(@Param("taskId") Long taskId, @Param("subTaskId") Long subTaskId);

    /** 物理删除某任务的全部记录（任务级联删除时使用）。 */
    @Delete("DELETE FROM task_execution_record WHERE task_id = #{taskId}")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);

    /** 物理删除某子任务的旧记录（rework 覆盖语义）。 */
    @Delete("DELETE FROM task_execution_record WHERE task_id = #{taskId} AND sub_task_id = #{subTaskId}")
    int physicalDeleteByTaskIdAndSubTaskId(@Param("taskId") Long taskId, @Param("subTaskId") Long subTaskId);
}