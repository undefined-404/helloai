package com.helloai.core.task.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.workflow.entity.WorkflowInstance;
import org.apache.ibatis.annotations.Mapper;

/**
 * WorkflowInstance Mapper（N-001，C1-S2）。
 */
@Mapper
public interface WorkflowInstanceMapper extends BaseMapper<WorkflowInstance> {
}
