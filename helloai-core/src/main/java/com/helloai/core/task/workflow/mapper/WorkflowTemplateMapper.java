package com.helloai.core.task.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.task.workflow.entity.WorkflowTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * WorkflowTemplate Mapper（N-001，C1-S1）。
 */
@Mapper
public interface WorkflowTemplateMapper extends BaseMapper<WorkflowTemplate> {
}
