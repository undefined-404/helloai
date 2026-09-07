package com.helloai.core.agent.event.impl;

import com.helloai.core.agent.entity.AgentEvent;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.agent.mapper.AgentEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 0 A6：Agent 事件流读侧投影测试。
 *
 * <p>验证 {@link AgentEventQueryServiceImpl#traceBySubTaskId} 的投影口径
 * （entity → VO 字段一一映射）与顺序透传；排序本身由 mapper 保证，不在此重复编排。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentEventQueryServiceImplTest {

    @Mock
    private AgentEventMapper agentEventMapper;

    @InjectMocks
    private AgentEventQueryServiceImpl queryService;

    @Test
    @DisplayName("subTaskId 为空 → 空列表且不触达 mapper")
    void shouldReturnEmptyWhenSubTaskIdNull() {
        assertThat(queryService.traceBySubTaskId(null)).isEmpty();
        verifyNoInteractions(agentEventMapper);
    }

    @Test
    @DisplayName("按序投影：entity 字段完整映射到 VO 且顺序透传")
    void shouldProjectAndPreserveOrder() {
        AgentEvent first = entity("e1", "run-1-1", 1, 1, "agent_started", OffsetDateTime.now());
        AgentEvent second = entity("e2", "run-1-1", 1, 3, "tool_call_started", OffsetDateTime.now());
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of(first, second));

        List<AgentEventTraceItem> items = queryService.traceBySubTaskId(10L);

        assertThat(items).hasSize(2);

        AgentEventTraceItem a = items.get(0);
        assertThat(a.getEventId()).isEqualTo("e1");
        assertThat(a.getRunId()).isEqualTo("run-1-1");
        assertThat(a.getTaskId()).isEqualTo(100L);
        assertThat(a.getSubTaskId()).isEqualTo(10L);
        assertThat(a.getTurn()).isEqualTo(1);
        assertThat(a.getStep()).isEqualTo(1);
        assertThat(a.getEventType()).isEqualTo("agent_started");
        assertThat(a.getAgentId()).isEqualTo(3L);
        assertThat(a.getPayload()).containsEntry("k", "v");
        assertThat(a.getCreateTime()).isNotNull();

        assertThat(items.get(1).getEventId()).isEqualTo("e2");
        assertThat(items.get(1).getStep()).isEqualTo(3);
    }

    @Test
    @DisplayName("mapper 返回空 → 空列表")
    void shouldReturnEmptyWhenNoEvents() {
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of());
        assertThat(queryService.traceBySubTaskId(10L)).isEmpty();
    }

    private AgentEvent entity(String eventId, String runId, int turn, int step,
                              String eventType, OffsetDateTime createTime) {
        AgentEvent e = new AgentEvent();
        e.setEventId(eventId);
        e.setRunId(runId);
        e.setTaskId(100L);
        e.setSubTaskId(10L);
        e.setTurn(turn);
        e.setStep(step);
        e.setEventType(eventType);
        e.setAgentId(3L);
        e.setPayload(Map.of("k", "v"));
        e.setCreateTime(createTime);
        return e;
    }
}