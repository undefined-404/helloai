package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.mapper.ActivityLogMapper;
import com.helloai.core.task.service.ActivityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 活动日志基础服务实现。提供 activity_log 表的通用查询与写入能力。
 */
@Slf4j
@Service
public class ActivityLogServiceImpl extends ServiceImpl<ActivityLogMapper, ActivityLog>
        implements ActivityLogService {

    @Override
    public IPage<ActivityLog> list(Long agentId, Long subTaskId, int page, int pageSize) {
        LambdaQueryWrapper<ActivityLog> wrapper = new LambdaQueryWrapper<ActivityLog>()
                .eq(agentId != null, ActivityLog::getAgentId, agentId)
                .eq(subTaskId != null, ActivityLog::getSubTaskId, subTaskId)
                .orderByDesc(ActivityLog::getCreateTime);

        if (page <= 0) {
            // 不分页：把全量列表包装成 IPage 返回，便于 Controller 统一处理
            List<ActivityLog> all = list(wrapper);
            Page<ActivityLog> full = new Page<>(1, all == null ? 0 : all.size());
            if (all != null) {
                full.setRecords(all);
            }
            return full;
        }
        return page(new Page<>(page, pageSize), wrapper);
    }

    @Override
    public List<ActivityLog> listAll(String level, String source) {
        LambdaQueryWrapper<ActivityLog> wrapper = new LambdaQueryWrapper<ActivityLog>()
                .eq(level != null && !level.isBlank(), ActivityLog::getLevel, level)
                .eq(source != null && !source.isBlank(), ActivityLog::getSource, source)
                .orderByDesc(ActivityLog::getCreateTime);
        List<ActivityLog> result = list(wrapper);
        return result != null ? result : Collections.emptyList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ActivityLog record(Long agentId, String action, String level, String source,
                              Long subTaskId, Map<String, Object> detail) {
        ActivityLog entity = new ActivityLog();
        entity.setAgentId(agentId);
        entity.setAction(action);
        entity.setLevel(level != null ? level : "INFO");
        entity.setSource(source != null ? source : "agent");
        entity.setSubTaskId(subTaskId);
        entity.setDetail(detail);
        save(entity);
        log.info("活动日志写入: agentId={}, action={}, subTaskId={}", agentId, action, subTaskId);
        return entity;
    }
}
