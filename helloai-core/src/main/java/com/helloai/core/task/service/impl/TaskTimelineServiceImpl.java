package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.event.AgentEventQueryService;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 任务事件时间线服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskTimelineServiceImpl extends ServiceImpl<TaskTimelineMapper, TaskTimeline>
        implements TaskTimelineService {

    private final AgentEventQueryService agentEventQueryService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordEvent(Long taskId,
                            Long subTaskId,
                            String eventType,
                            AgentRole role,
                            Long agentId,
                            Map<String, Object> payload) {
        TaskTimeline timeline = new TaskTimeline();
        timeline.setTaskId(taskId);
        timeline.setSubTaskId(subTaskId);
        timeline.setEventType(eventType);
        timeline.setRole(role);
        timeline.setAgentId(agentId);
        timeline.setPayload(payload != null ? payload : Map.of());
        save(timeline);
        log.debug("TaskTimeline event recorded: type={}, agentId={}, role={}", eventType, agentId, role);
    }

    @Override
    public List<TaskTimeline> listBySubTaskId(Long subTaskId) {
        if (subTaskId == null) {
            return Collections.emptyList();
        }
        // 粗粒度业务/角色事件（task_timeline，既有载体，ADR-001 §4 不迁移）
        List<TaskTimeline> coarse = lambdaQuery()
                .eq(TaskTimeline::getSubTaskId, subTaskId)
                .orderByAsc(TaskTimeline::getId)
                .list();
        // 细粒度执行轨迹（agent_event，A6 并轨读消费面）
        List<TaskTimeline> trace = agentEventQueryService.traceBySubTaskId(subTaskId).stream()
                .map(this::toTimeline)
                .toList();
        return merge(coarse, trace);
    }

    /**
     * 合并 task_timeline 与 agent_event 时间线，按写入时序升序（createTime ASC，同刻按 id ASC）。
     */
    List<TaskTimeline> merge(List<TaskTimeline> coarse, List<TaskTimeline> trace) {
        return Stream.concat(coarse.stream(), trace.stream())
                .sorted(Comparator
                        .comparing((TaskTimeline t) -> t.getCreateTime() != null ? t.getCreateTime() : OffsetDateTime.MIN)
                        .thenComparing(TaskTimeline::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** agent_event 轨迹投影 → TaskTimeline 条目（id 用 agent_event 主键，role 缺省 null）。 */
    TaskTimeline toTimeline(AgentEventTraceItem e) {
        TaskTimeline t = new TaskTimeline();
        t.setId(e.getId());
        t.setTaskId(e.getTaskId());
        t.setSubTaskId(e.getSubTaskId());
        t.setEventType(e.getEventType());
        t.setAgentId(e.getAgentId());
        t.setPayload(e.getPayload() != null ? e.getPayload() : Map.of());
        t.setCreateTime(e.getCreateTime());
        return t;
    }
}
