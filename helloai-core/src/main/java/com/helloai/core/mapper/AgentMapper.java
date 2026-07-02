package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.entity.Agent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
}
