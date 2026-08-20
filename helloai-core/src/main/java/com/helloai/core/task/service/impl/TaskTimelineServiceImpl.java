package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 任务事件时间线服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskTimelineServiceImpl extends ServiceImpl<TaskTimelineMapper, TaskTimeline>
        implements TaskTimelineService {

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
        return lambdaQuery()
                .eq(TaskTimeline::getSubTaskId, subTaskId)
                .orderByAsc(TaskTimeline::getId)
                .list();
    }
}
