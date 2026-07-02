package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.entity.AgentOutboxEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentOutboxEventMapper extends BaseMapper<AgentOutboxEvent> {
}
