package com.helloai.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.entity.TaskTimeline;
import org.apache.ibatis.annotations.Mapper;

/**
 * TaskTimeline Mapper。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper} 提供基础 CRUD，
 * 自定义方法集中在 {@code TaskTimelineMapper.xml}（主要是 JSONB payload 字段处理）。</p>
 */
@Mapper
public interface TaskTimelineMapper extends BaseMapper<TaskTimeline> {
}