package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.Task;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {

    /** 物理删除任务主行（@TableLogic 会把普通 delete 改写为软删，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM task WHERE id = #{taskId}")
    int physicalDeleteById(@Param("taskId") Long taskId);
}
