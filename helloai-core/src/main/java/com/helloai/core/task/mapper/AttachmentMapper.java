package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.Attachment;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AttachmentMapper extends BaseMapper<Attachment> {

    /** 物理删除某任务下全部附件行（外键引用 sub_task.id，必须先于子任务删除，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM attachment WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}