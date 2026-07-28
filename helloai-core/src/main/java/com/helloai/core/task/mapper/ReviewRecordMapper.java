package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.ReviewRecord;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReviewRecordMapper extends BaseMapper<ReviewRecord> {

    /** 统计某任务下全部子任务的审查记录数（删除前风险提示用）。 */
    @Select("SELECT COUNT(*) FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int countByTaskId(@Param("taskId") Long taskId);

    /** 物理删除某任务下全部审查记录（外键引用 sub_task.id，必须先于子任务删除，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM review_record WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}
