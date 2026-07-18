package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.AgentOutboxEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentOutboxEventMapper extends BaseMapper<AgentOutboxEvent> {
}
