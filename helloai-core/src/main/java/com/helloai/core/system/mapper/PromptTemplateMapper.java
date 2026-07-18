package com.helloai.core.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.system.entity.PromptTemplate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {
}
