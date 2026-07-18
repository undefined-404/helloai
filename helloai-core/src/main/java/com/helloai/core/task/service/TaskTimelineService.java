package com.helloai.core.task.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.task.entity.TaskTimeline;
import com.helloai.core.task.mapper.TaskTimelineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 任务事件时间线服务（v2.4 阶段 4.2）。
 *
 * <p>提供事件记录的入口：
 * <ul>
 *   <li>{@link #recordEvent}：通用事件记录（用于 AgentHealthCheckTask 等）</li>
 * </ul>
 * </p>
 *
 * <p>设计原则：
 * <ul>
 *   <li>幂等性：recordEvent 不去重（事件本身就是审计，重写是 OK 的）</li>
 *   <li>异步写：单条插入同步完成（数据库写入毫秒级），无需异步队列</li>
 *   <li>payload 自由：调用方传入 Map，JSONB 存储</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskTimelineService extends ServiceImpl<TaskTimelineMapper, TaskTimeline> {

    /**
     * 记录一条任务事件。
     *
     * @param taskId     主任务 ID（可空，用于系统级事件如 agent_offline）
     * @param subTaskId  子任务 ID（可空）
     * @param eventType  事件类型（snake_case，如 agent_offline / task_assigned）
     * @param role       事件产生方角色（PLANNER / EXECUTOR / REVIEWER / PATROL / SYSTEM）
     * @param agentId    关联 Agent ID（可空）
     * @param payload    事件负载（可空，空时存空 Map）
     */
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
}