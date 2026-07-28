package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.TaskTimeline;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * TaskTimeline Mapper。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper} 提供基础 CRUD，
 * 自定义方法集中在 {@code TaskTimelineMapper.xml}（主要是 JSONB payload 字段处理）。</p>
 */
@Mapper
public interface TaskTimelineMapper extends BaseMapper<TaskTimeline> {

    /** 物理删除某任务的全部时间线审计记录（仅供任务级联删除使用）。 */
    @Delete("DELETE FROM task_timeline WHERE task_id = #{taskId}")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}