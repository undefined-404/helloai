package com.helloai.core.agent.event;

import com.helloai.common.constant.AgentEventType;
import com.helloai.common.constant.OutboxStatus;
import com.helloai.core.agent.entity.AgentEvent;
import com.helloai.core.agent.entity.AgentOutboxEvent;
import com.helloai.core.agent.event.impl.AgentEventRecorderImpl;
import com.helloai.core.agent.mapper.AgentEventMapper;
import com.helloai.core.agent.service.AgentOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * Phase 0 B1：Agent 事件记录器双写测试。
 *
 * <p>验证 {@code agent_event}（轨迹主表）与 {@code agent_outbox_event}（Outbox）
 * 同事务双写、共享同一 eventId（B3 对账键）、payload 冗余事件上下文。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentEventRecorderImplTest {

    @Mock
    private AgentEventMapper agentEventMapper;

    @Mock
    private AgentOutboxService agentOutboxService;

    @InjectMocks
    private AgentEventRecorderImpl recorder;

    @Test
    @DisplayName("双写成功：agent_event 与 outbox 共享同一 eventId，outbox 为 PENDING 待投递")
    void shouldWriteBothTablesWithSharedEventId() {
        Map<String, Object> payload = Map.of("skill", "web-search");

        recorder.record("run-1-1", 10L, 11L, 1, 2, AgentEventType.TOOL_CALL_COMPLETED, 3L, payload);

        ArgumentCaptor<AgentEvent> eventCaptor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(agentEventMapper).insert(eventCaptor.capture());
        AgentEvent event = eventCaptor.getValue();
        assertThat(event.getRunId()).isEqualTo("run-1-1");
        assertThat(event.getTaskId()).isEqualTo(10L);
        assertThat(event.getSubTaskId()).isEqualTo(11L);
        assertThat(event.getTurn()).isEqualTo(1);
        assertThat(event.getStep()).isEqualTo(2);
        assertThat(event.getEventType()).isEqualTo("tool_call_completed");
        assertThat(event.getAgentId()).isEqualTo(3L);
        assertThat(event.getPayload()).containsEntry("skill", "web-search");

        ArgumentCaptor<AgentOutboxEvent> outboxCaptor = ArgumentCaptor.forClass(AgentOutboxEvent.class);
        verify(agentOutboxService).save(outboxCaptor.capture());
        AgentOutboxEvent outbox = outboxCaptor.getValue();
        // B3 对账键：两表共享同一 eventId
        assertThat(outbox.getEventId()).isEqualTo(event.getEventId());
        assertThat(outbox.getEventType()).isEqualTo("tool_call_completed");
        assertThat(outbox.getRoutingKey()).isEqualTo("agent.event.tool_call_completed");
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getRetryCount()).isZero();
        // payload 冗余事件上下文，MQ 消费者免回查 DB
        assertThat(outbox.getPayload())
                .containsEntry("eventId", event.getEventId())
                .containsEntry("runId", "run-1-1")
                .containsEntry("subTaskId", 11L)
                .containsEntry("turn", 1)
                .containsEntry("step", 2)
                .containsEntry("agentId", 3L)
                .containsEntry("skill", "web-search");
    }

    @Test
    @DisplayName("null payload 存空 Map（JSONB 不为 null）")
    void shouldStoreEmptyPayloadWhenNull() {
        recorder.record("run-2-1", 20L, null, 1, 0, AgentEventType.AGENT_STARTED, 3L, null);

        ArgumentCaptor<AgentEvent> eventCaptor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(agentEventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getPayload()).isNotNull().isEmpty();

        ArgumentCaptor<AgentOutboxEvent> outboxCaptor = ArgumentCaptor.forClass(AgentOutboxEvent.class);
        verify(agentOutboxService).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getPayload()).isNotNull().isNotEmpty();
    }

    @Test
    @DisplayName("eventType 为空 → 拒绝记录")
    void shouldRejectNullEventType() {
        assertThatThrownBy(() -> recorder.record("run-3-1", 30L, null, 1, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    @DisplayName("runId 为空 → 拒绝记录")
    void shouldRejectBlankRunId() {
        assertThatThrownBy(() -> recorder.record("  ", 30L, null, 1, 0, AgentEventType.RUN_CREATED, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @Test
    @DisplayName("payload 为可变更 Map 时不被 outbox 写回污染（各自持有副本）")
    void shouldNotMutateCallerPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("expected", "keep");

        recorder.record("run-4-1", 40L, 41L, 1, 0, AgentEventType.CONTEXT_BUILT, 3L, payload);

        // 调用方入参不应被补入事件派发字段
        assertThat(payload).containsOnlyKeys("expected");
    }
}