package com.helloai.core.agent.event.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 0 A6 / A7：Agent 事件流读侧投影测试。
 *
 * <p>验证 {@link AgentEventQueryServiceImpl#traceBySubTaskId} / {@link #traceByRunId} /
 * {@link #traceByTaskId} / {@link #pageAuditByTaskId} 的投影口径（entity → VO 字段一一映射）与顺序透传；
 * 排序本身由 mapper 保证，不在此重复编排。{@code traceByTaskId} 额外验证 runId 规则
 * 收敛在 service 层（ADR-001 §3.1，G-006 消费面易用性增量）。</p>
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
        AgentEvent first = entity(1L, "e1", "run-1-1", 1, 1, "agent_started", OffsetDateTime.now());
        AgentEvent second = entity(2L, "e2", "run-1-1", 1, 3, "tool_call_started", OffsetDateTime.now());
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of(first, second));

        List<AgentEventTraceItem> items = queryService.traceBySubTaskId(10L);

        assertThat(items).hasSize(2);

        AgentEventTraceItem a = items.get(0);
        assertThat(a.getId()).isEqualTo(1L);
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

    @Test
    @DisplayName("runId 为空/空白 → 空列表且不触达 mapper")
    void shouldReturnEmptyWhenRunIdBlank() {
        assertThat(queryService.traceByRunId(null)).isEmpty();
        assertThat(queryService.traceByRunId("  ")).isEmpty();
        verifyNoInteractions(agentEventMapper);
    }

    @Test
    @DisplayName("按 runId 重建轨迹：跨 Turn 全量投影且顺序透传")
    void shouldRebuildRunTrajectory() {
        AgentEvent turn1 = entity(1L, "e1", "run-7-1", 1, 1, "agent_started", OffsetDateTime.now());
        AgentEvent turn2 = entity(2L, "e2", "run-7-1", 2, 5, "skill_resolved", OffsetDateTime.now());
        when(agentEventMapper.selectByRunIdOrdered("run-7-1")).thenReturn(List.of(turn1, turn2));

        List<AgentEventTraceItem> items = queryService.traceByRunId("run-7-1");

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getRunId()).isEqualTo("run-7-1");
        assertThat(items.get(0).getTurn()).isEqualTo(1);
        assertThat(items.get(1).getTurn()).isEqualTo(2);
        assertThat(items.get(1).getStep()).isEqualTo(5);
        assertThat(items.get(1).getEventType()).isEqualTo("skill_resolved");
    }

    @Test
    @DisplayName("traceByTaskId：按 run-{taskId}-1 规则委托 Run 级查询 + 投影完整")
    void shouldDelegateTaskIdToRunIdRule() {
        AgentEvent first = entity(1L, "e1", "run-42-1", 1, 1, "agent_started", OffsetDateTime.now());
        when(agentEventMapper.selectByRunIdOrdered("run-42-1")).thenReturn(List.of(first));

        List<AgentEventTraceItem> items = queryService.traceByTaskId(42L);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getRunId()).isEqualTo("run-42-1");
        assertThat(items.get(0).getTaskId()).isEqualTo(100L);
        assertThat(items.get(0).getEventType()).isEqualTo("agent_started");
        verify(agentEventMapper).selectByRunIdOrdered("run-42-1");
    }

    @Test
    @DisplayName("traceByTaskId：taskId 为空 → 空列表且不触达 mapper")
    void shouldReturnEmptyWhenTaskIdNull() {
        assertThat(queryService.traceByTaskId(null)).isEmpty();
        verifyNoInteractions(agentEventMapper);
    }

    @Test
    @DisplayName("taskId 为空 → 空分页且不触达 mapper")
    void shouldReturnEmptyPageWhenTaskIdNull() {
        IPage<AgentEventTraceItem> page = queryService.pageAuditByTaskId(null, null, null, null, 1, 10);
        assertThat(page.getRecords()).isEmpty();
        assertThat(page.getTotal()).isZero();
        verifyNoInteractions(agentEventMapper);
    }

    @Test
    @DisplayName("按 task 分页审计：投影 + total/pages 元数据透传")
    void shouldPageAuditByTaskId() {
        AgentEvent first = entity(1L, "e1", "run-100-1", 1, 1, "agent_started", OffsetDateTime.now());
        AgentEvent second = entity(2L, "e2", "run-100-1", 1, 0, "agent_completed", OffsetDateTime.now());
        Page<AgentEvent> resultPage = new Page<>(1, 2);
        resultPage.setTotal(2);
        resultPage.setRecords(List.of(first, second));
        OffsetDateTime start = OffsetDateTime.parse("2026-10-01T00:00:00+08:00");
        OffsetDateTime end = OffsetDateTime.parse("2026-10-08T23:59:59+08:00");
        when(agentEventMapper.selectPageAuditByTaskId(any(), eq(100L), eq("agent_started"), eq(start), eq(end)))
                .thenReturn(resultPage);

        IPage<AgentEventTraceItem> page = queryService.pageAuditByTaskId(100L, "agent_started", "2026-10-01 00:00:00", "2026-10-08 23:59:59", 1, 2);

        assertThat(page.getRecords()).hasSize(2);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getRecords().get(0).getEventType()).isEqualTo("agent_started");
        assertThat(page.getRecords().get(1).getEventType()).isEqualTo("agent_completed");
    }

    @Test
    @DisplayName("按 task 分页审计：eventType 为空时透传不过滤")
    void shouldPassBlankEventTypeThrough() {
        Page<AgentEvent> resultPage = new Page<>(1, 10);
        when(agentEventMapper.selectPageAuditByTaskId(any(), eq(100L), eq("  "), eq((OffsetDateTime) null), eq((OffsetDateTime) null)))
                .thenReturn(resultPage);

        IPage<AgentEventTraceItem> page = queryService.pageAuditByTaskId(100L, "  ", null, null, 1, 10);

        assertThat(page.getRecords()).isEmpty();
        verify(agentEventMapper).selectPageAuditByTaskId(any(), eq(100L), eq("  "), eq((OffsetDateTime) null), eq((OffsetDateTime) null));
    }

    @Test
    @DisplayName("Audit 时间参数解析：ISO 形态直接 parse，本地形态按系统时区装配")
    void shouldParseAuditTimeStringIntoOffsetDate() {
        Page<AgentEvent> resultPage = new Page<>(1, 10);
        when(agentEventMapper.selectPageAuditByTaskId(any(), eq(100L), any(), any(), any())).thenReturn(resultPage);

        // ISO 带时区 → 严格 parse
        queryService.pageAuditByTaskId(100L, null,
                "2026-10-01T08:00:00+08:00", "2026-10-08T20:00:00+08:00", 1, 10);
        verify(agentEventMapper).selectPageAuditByTaskId(any(), eq(100L), any(),
                eq(OffsetDateTime.parse("2026-10-01T08:00:00+08:00")),
                eq(OffsetDateTime.parse("2026-10-08T20:00:00+08:00")));

        // 隔离 mock 上下文，确保下面的 verify 锚定在「本地形态」这一次调用
        org.mockito.Mockito.clearInvocations(agentEventMapper);
        when(agentEventMapper.selectPageAuditByTaskId(any(), eq(100L), any(), any(), any())).thenReturn(resultPage);

        // yyyy-MM-dd HH:mm:ss → 按系统默认时区装配
        java.time.ZoneOffset zone = java.time.OffsetDateTime.now().getOffset();
        queryService.pageAuditByTaskId(100L, null, "2026-10-01 08:00:00", "2026-10-08 20:00:00", 1, 10);
        OffsetDateTime expectedStart = OffsetDateTime.of(
                java.time.LocalDateTime.parse("2026-10-01 08:00:00", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                zone);
        OffsetDateTime expectedEnd = OffsetDateTime.of(
                java.time.LocalDateTime.parse("2026-10-08 20:00:00", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                zone);
        verify(agentEventMapper).selectPageAuditByTaskId(any(), eq(100L), any(), eq(expectedStart), eq(expectedEnd));
    }

    @Test
    @DisplayName("Audit 时间参数无法解析时降级为 null（不阻塞查询）")
    void shouldFallbackToNullOnUnparseableTime() {
        Page<AgentEvent> resultPage = new Page<>(1, 10);
        when(agentEventMapper.selectPageAuditByTaskId(any(), eq(100L), any(), any(), any())).thenReturn(resultPage);

        queryService.pageAuditByTaskId(100L, null, "garbled", "also-garbled", 1, 10);

        verify(agentEventMapper).selectPageAuditByTaskId(any(), eq(100L), any(), eq((OffsetDateTime) null), eq((OffsetDateTime) null));
    }

    private AgentEvent entity(Long id, String eventId, String runId, int turn, int step,
                              String eventType, OffsetDateTime createTime) {
        AgentEvent e = new AgentEvent();
        e.setId(id);
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