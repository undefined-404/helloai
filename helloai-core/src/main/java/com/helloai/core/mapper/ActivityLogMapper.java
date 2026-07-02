package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.entity.ActivityLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ActivityLogMapper extends BaseMapper<ActivityLog> {
}
