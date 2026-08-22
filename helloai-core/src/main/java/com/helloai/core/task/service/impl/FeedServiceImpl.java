package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.entity.ActivityLog;
import com.helloai.core.task.service.ActivityLogService;
import com.helloai.core.task.service.FeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Feed 聚合服务实现（前端活动流 {@code /api/feed} 数据源）。
 *
 * <p>聚合 ActivityLog 与 Agent 两张表的活动日志分页列表与 Agent 摘要列表，
 * 为避免在 Controller 直接注入 Mapper，将查询与名称解析下移至本服务；
 * agent 域数据经 AgentService 收口（§6.140），不再直捅 agent.mapper。</p>
 */
@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private final ActivityLogService activityLogService;
    private final AgentService agentService;

    @Override
    public Page<ActivityLog> listActivityLogs(int page, int pageSize, String level, String source) {
        LambdaQueryWrapper<ActivityLog> wrapper = new LambdaQueryWrapper<ActivityLog>()
                .eq(level != null && !level.isBlank(), ActivityLog::getLevel, level)
                .eq(source != null && !source.isBlank(), ActivityLog::getSource, source)
                .orderByDesc(ActivityLog::getCreateTime);
        return activityLogService.page(new Page<>(page, pageSize), wrapper);
    }

    @Override
    public Map<Long, String> resolveAgentNames(List<ActivityLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = logs.stream()
                .map(ActivityLog::getAgentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Agent> agents = agentService.listByIds(ids);
        if (agents == null || agents.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new LinkedHashMap<>();
        for (Agent a : agents) {
            if (a.getId() != null && a.getName() != null) {
                result.put(a.getId(), a.getName());
            }
        }
        return result;
    }

    @Override
    public List<Agent> listAgentSummaries() {
        List<Agent> list = agentService.listSummaries();
        return list != null ? list : Collections.emptyList();
    }
}
