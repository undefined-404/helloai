package com.helloai.core.agent.event.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.core.agent.entity.AgentEvent;
import com.helloai.core.agent.event.AgentEventQueryService;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.agent.mapper.AgentEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Agent 事件流读侧查询实现（Phase 0 A6 / A7）。
 *
 * <p>纯读服务：{@link #traceBySubTaskId} / {@link #traceByRunId} /
 * {@link #pageAuditByTaskId} 将 {@code agent_event}（mapper 已按对应时序返回）
 * 投影为不可变 {@link AgentEventTraceItem}，不落库、不写状态。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentEventQueryServiceImpl implements AgentEventQueryService {

    private final AgentEventMapper agentEventMapper;

    @Override
    public List<AgentEventTraceItem> traceBySubTaskId(Long subTaskId) {
        if (subTaskId == null) {
            return Collections.emptyList();
        }
        return agentEventMapper.selectBySubTaskIdOrdered(subTaskId).stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    public List<AgentEventTraceItem> traceByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return Collections.emptyList();
        }
        return agentEventMapper.selectByRunIdOrdered(runId).stream()
                .map(this::toItem)
                .toList();
    }

    @Override
    public IPage<AgentEventTraceItem> pageAuditByTaskId(Long taskId, String eventType, long pageNum, long pageSize) {
        if (taskId == null) {
            return new Page<>(pageNum, pageSize);
        }
        Page<AgentEvent> page = new Page<>(pageNum, pageSize);
        IPage<AgentEvent> result = agentEventMapper.selectPageAuditByTaskId(page, taskId, eventType);
        return result.convert(this::toItem);
    }

    private AgentEventTraceItem toItem(AgentEvent e) {
        return AgentEventTraceItem.builder()
                .id(e.getId())
                .eventId(e.getEventId())
                .runId(e.getRunId())
                .taskId(e.getTaskId())
                .subTaskId(e.getSubTaskId())
                .turn(e.getTurn())
                .step(e.getStep())
                .eventType(e.getEventType())
                .agentId(e.getAgentId())
                .payload(e.getPayload())
                .createTime(e.getCreateTime())
                .build();
    }
}