package com.helloai.core.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.entity.Module;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ModuleMapper extends BaseMapper<Module> {

    /** 物理删除某任务下全部模块（module.task_id 对 task.id 有外键，仅供任务级联删除使用）。 */
    @Delete("DELETE FROM module WHERE task_id = #{taskId}")
    int physicalDeleteByTaskId(@Param("taskId") Long taskId);
}