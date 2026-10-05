package com.helloai.core.agent.event;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.AgentEvent;
import com.helloai.core.agent.mapper.AgentEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentEventForkService 单测（B3 Fork·Claude Code/Codex 同款最朴素形态）。
 *
 * <p>验证契约：① 复制全部事件到新 run_id 且 event_id 全新；② seq 跨次递增；
 * ③ payload / turn / step / event_type / agent_id 逐字保留；④ 无事件抛 BizException；
 * ⑤ taskId null 抛 IllegalArgumentException；⑥ 不修改读出的原事件对象。</p>
 *
 * <p><b>不验证</b>：outbox 不被复制（ForkService 无 outbox 依赖，结构上不可能调到）；
 * ASSIGN_ID 填充（mock 不模拟 MyBatis-Plus 行为，insert 的 id 为 null 是预期）；
 * 真实 DB 写入（需 IT，本轮单测覆盖不到，留 PS1/E2E）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentEventForkService（B3 Fork·快照复制）")
class AgentEventForkServiceTest {

    private static final Long TASK_ID = 33L;
    private static final String ORIGIN_RUN_ID = "run-33-1";
    private static final String FORK_PREFIX = ORIGIN_RUN_ID + "-fork-";

    @Mock
    private AgentEventMapper agentEventMapper;

    private AgentEventForkService service;

    @BeforeEach
    void setUp() {
        service = new AgentEventForkService(agentEventMapper);
    }

    @Test
    @DisplayName("复制全部事件到新 run_id，event_id 全新且不与原重叠")
    void shouldCopyAllEventsWithNewRunIdAndFreshEventIds() {
        List<AgentEvent> origin = List.of(
                event("evt-origin-1", 1, 3, "tool_call_started"),
                event("evt-origin-2", 1, 4, "tool_call_completed"),
                event("evt-origin-3", 1, 5, "skill_resolved"));
        when(agentEventMapper.selectByRunIdOrdered(ORIGIN_RUN_ID)).thenReturn(origin);
        when(agentEventMapper.selectMaxForkSeq(TASK_ID, FORK_PREFIX)).thenReturn(0);

        String newRunId = service.forkRun(TASK_ID);

        assertThat(newRunId).isEqualTo("run-33-1-fork-1");
        ArgumentCaptor<AgentEvent> captor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(agentEventMapper, times(3)).insert(captor.capture());
        List<AgentEvent> forked = captor.getAllValues();
        assertThat(forked).hasSize(3);
        // 全部新 run_id
        assertThat(forked).allMatch(e -> "run-33-1-fork-1".equals(e.getRunId()));
        // event_id 全新且互不重复
        Set<String> newEventIds = forked.stream().map(AgentEvent::getEventId).collect(Collectors.toSet());
        assertThat(newEventIds).hasSize(3);
        // 新 event_id 不与原重叠
        assertThat(newEventIds).doesNotContain("evt-origin-1", "evt-origin-2", "evt-origin-3");
        // remark 标注 fork 来源
        assertThat(forked).allMatch(e -> ("forked from " + ORIGIN_RUN_ID).equals(e.getRemark()));
    }

    @Test
    @DisplayName("连续 fork 两次：seq 递增（fork-1 / fork-2）")
    void shouldIncrementSeqAcrossForks() {
        when(agentEventMapper.selectByRunIdOrdered(ORIGIN_RUN_ID))
                .thenReturn(List.of(event("evt-1", 1, 1, "agent_started")));
        when(agentEventMapper.selectMaxForkSeq(TASK_ID, FORK_PREFIX)).thenReturn(0, 1);

        String first = service.forkRun(TASK_ID);
        String second = service.forkRun(TASK_ID);

        assertThat(first).isEqualTo("run-33-1-fork-1");
        assertThat(second).isEqualTo("run-33-1-fork-2");
    }

    @Test
    @DisplayName("payload / turn / step / event_type / agent_id / task_id 逐字保留")
    void shouldPreservePayloadAndSteps() {
        Map<String, Object> payload = Map.of("tool", "pullTasks", "success", true, "output", "done");
        AgentEvent src = event("evt-origin", 2, 4, "tool_call_completed");
        src.setPayload(payload);
        when(agentEventMapper.selectByRunIdOrdered(ORIGIN_RUN_ID)).thenReturn(List.of(src));
        when(agentEventMapper.selectMaxForkSeq(TASK_ID, FORK_PREFIX)).thenReturn(0);

        service.forkRun(TASK_ID);

        ArgumentCaptor<AgentEvent> captor = ArgumentCaptor.forClass(AgentEvent.class);
        verify(agentEventMapper).insert(captor.capture());
        AgentEvent forked = captor.getValue();
        assertThat(forked.getTurn()).isEqualTo(2);
        assertThat(forked.getStep()).isEqualTo(4);
        assertThat(forked.getEventType()).isEqualTo("tool_call_completed");
        assertThat(forked.getAgentId()).isEqualTo(11L);
        assertThat(forked.getTaskId()).isEqualTo(TASK_ID);
        assertThat(forked.getSubTaskId()).isEqualTo(22L);
        assertThat(forked.getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("原 Run 无事件 → BizException，不产生 insert")
    void shouldFailWhenNoEventsToFork() {
        when(agentEventMapper.selectByRunIdOrdered(ORIGIN_RUN_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.forkRun(TASK_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无事件可 fork");

        verify(agentEventMapper, never()).insert(any(AgentEvent.class));
    }

    @Test
    @DisplayName("taskId 为 null → IllegalArgumentException，不查事件流")
    void shouldFailWhenTaskIdNull() {
        assertThatThrownBy(() -> service.forkRun(null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(agentEventMapper, never()).selectByRunIdOrdered(any());
        verify(agentEventMapper, never()).selectMaxForkSeq(any(), any());
    }

    @Test
    @DisplayName("不修改读出的原事件对象（fork 是只读复制）")
    void shouldNotMutateOriginEvents() {
        AgentEvent src = event("evt-origin", 1, 1, "agent_started");
        when(agentEventMapper.selectByRunIdOrdered(ORIGIN_RUN_ID)).thenReturn(List.of(src));
        when(agentEventMapper.selectMaxForkSeq(TASK_ID, FORK_PREFIX)).thenReturn(0);

        service.forkRun(TASK_ID);

        // 原事件对象保持原值（未被 set 新 run_id / event_id）
        assertThat(src.getRunId()).isEqualTo(ORIGIN_RUN_ID);
        assertThat(src.getEventId()).isEqualTo("evt-origin");
    }

    /** 构造原事件夹具（id/eventId/runId/turn/step/eventType 各异，便于断言区分）。 */
    private AgentEvent event(String eventId, int turn, int step, String eventType) {
        AgentEvent e = new AgentEvent();
        e.setId(100L + step);
        e.setEventId(eventId);
        e.setRunId(ORIGIN_RUN_ID);
        e.setTaskId(TASK_ID);
        e.setSubTaskId(22L);
        e.setTurn(turn);
        e.setStep(step);
        e.setEventType(eventType);
        e.setAgentId(11L);
        e.setPayload(Map.of("tool", eventType));
        return e;
    }
}
