package com.helloai.core.agent.event.impl;

import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.OutboxStatus;
import com.helloai.core.agent.entity.AgentEvent;
import com.helloai.core.agent.entity.AgentOutboxEvent;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.mapper.AgentEventMapper;
import com.helloai.core.agent.service.AgentOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 事件记录器实现（Phase 0 B1）。
 *
 * <p>双写链路：{@code agent_event}（轨迹主表）→ {@code agent_outbox_event}
 * （Outbox 投递，routingKey = {@code agent.event.{type}}，由
 * {@code AgentEventCompensationTask} 周期投递 AGENT_TOPIC_EXCHANGE）。
 * 两表共享 {@code eventId}，MQ 消费端以其为幂等键（与现有
 * {@code agent_outbox_event} 事件同一契约）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventRecorderImpl implements AgentEventRecorder {

    private final AgentEventMapper agentEventMapper;
    private final AgentOutboxService agentOutboxService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(String runId, Long taskId, Long subTaskId, int turn, int step,
                       AgentEventType eventType, Long agentId, Map<String, Object> payload) {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null when recording agent event");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank when recording agent event");
        }
        // 双写共享 eventId：DB 幂等键 / MQ 消息幂等键 / B3 对账键 三者合一
        String eventId = UUID.randomUUID().toString().replace("-", "");

        // 1) append-only 轨迹主表
        AgentEvent event = new AgentEvent();
        event.setEventId(eventId);
        event.setRunId(runId);
        event.setTaskId(taskId);
        event.setSubTaskId(subTaskId);
        event.setTurn(turn);
        event.setStep(step);
        event.setEventType(eventType.code());
        event.setAgentId(agentId);
        event.setPayload(payload == null ? new HashMap<>() : payload);
        agentEventMapper.insert(event);

        // 2) Outbox 双写：payload 冗余 runId/taskId/subTaskId/turn/step/agentId，
        //    MQ 消费者无需回查 DB 即可还原事件上下文
        AgentOutboxEvent outbox = new AgentOutboxEvent();
        outbox.setEventId(eventId);
        outbox.setEventType(eventType.code());
        outbox.setRoutingKey("agent.event." + eventType.code());
        Map<String, Object> outboxPayload = new HashMap<>(event.getPayload());
        outboxPayload.put("eventId", eventId);
        outboxPayload.put("runId", runId);
        outboxPayload.put("taskId", taskId);
        outboxPayload.put("subTaskId", subTaskId);
        outboxPayload.put("turn", turn);
        outboxPayload.put("step", step);
        outboxPayload.put("agentId", agentId);
        outbox.setPayload(outboxPayload);
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setRetryCount(0);
        agentOutboxService.save(outbox);

        log.debug("Agent event recorded: eventId={}, type={}, runId={}, subTaskId={}",
                eventId, eventType.code(), runId, subTaskId);
    }
}