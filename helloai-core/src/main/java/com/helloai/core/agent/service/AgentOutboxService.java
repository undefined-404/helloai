package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.OutboxStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.AgentOutboxEvent;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.agent.mapper.AgentOutboxEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOutboxService extends ServiceImpl<AgentOutboxEventMapper, AgentOutboxEvent> {

    @Transactional(rollbackFor = Exception.class)
    public AgentOutboxEvent createEvent(SubTask subTask, SubTaskStatus newStatus) {
        AgentOutboxEvent event = new AgentOutboxEvent();
        event.setEventId(UUID.randomUUID().toString().replace("-", ""));
        event.setEventType("sub_task." + newStatus.name().toLowerCase());
        event.setRoutingKey(resolveRoutingKey(subTask, newStatus));
        Map<String, Object> payload = new HashMap<>(Map.of(
                "subTaskId", subTask.getId(),
                "taskId", subTask.getTaskId(),
                "status", newStatus.name(),
                "agentId", subTask.getAssignedAgentId() != null ? subTask.getAssignedAgentId() : 0L
        ));
        if (newStatus == SubTaskStatus.BLOCKED
                && subTask.getContext() != null
                && subTask.getContext().get("blockedReason") instanceof String reason
                && reason != null
                && !reason.isBlank()) {
            payload.put("blockedReason", reason);
        }
        event.setPayload(payload);
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        save(event);
        log.info("Outbox event created: eventId={}, type={}, subTaskId={}",
                event.getEventId(), event.getEventType(), subTask.getId());
        return event;
    }

    public List<AgentOutboxEvent> pollPending(int limit) {
        return list(new LambdaQueryWrapper<AgentOutboxEvent>()
                .eq(AgentOutboxEvent::getStatus, OutboxStatus.PENDING)
                .lt(AgentOutboxEvent::getRetryCount, 5)
                .and(w -> w.isNull(AgentOutboxEvent::getNextRetryTime)
                        .or().le(AgentOutboxEvent::getNextRetryTime, OffsetDateTime.now()))
                .orderByAsc(AgentOutboxEvent::getCreateTime)
                .last("LIMIT " + limit));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markSuccess(Long id) {
        lambdaUpdate()
                .eq(AgentOutboxEvent::getId, id)
                .set(AgentOutboxEvent::getStatus, OutboxStatus.SUCCESS)
                .setSql("retry_count = retry_count + 1")
                .update();
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailed(Long id, String error) {
        lambdaUpdate()
                .eq(AgentOutboxEvent::getId, id)
                .setSql("retry_count = retry_count + 1")
                .set(AgentOutboxEvent::getNextRetryTime, OffsetDateTime.now().plusSeconds(10))
                .set(AgentOutboxEvent::getErrorMsg, error)
                .update();
    }

    private String resolveRoutingKey(SubTask subTask, SubTaskStatus status) {
        return switch (status) {
            case ASSIGNED -> "agent.executor.assigned";
            case REVIEW -> "agent.reviewer.assigned";
            case REWORK -> "agent.executor.rework";
            case BLOCKED -> "agent.planner.blocked";
            case PAUSED -> "agent.executor.paused";
            case DONE -> "agent.planner.done";
            default -> "agent.system." + status.name().toLowerCase();
        };
    }
}
