package com.helloai.core.task.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.mapper.ActivityLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 活动日志基础服务。提供 activity_log 表的通用查询能力。
 * 复杂统计查询由 {@link AgentService} 通过 Mapper 直接完成。
 */
@Slf4j
@Service
public class ActivityLogService extends ServiceImpl<ActivityLogMapper, ActivityLog> {
}
