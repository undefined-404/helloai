package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.entity.RequestLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RequestLogMapper extends BaseMapper<RequestLog> {
}
