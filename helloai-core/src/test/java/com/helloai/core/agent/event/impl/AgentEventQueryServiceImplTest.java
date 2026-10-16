package com.helloai.core.agent.event.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.common.constant.AgentEventType;
import com.helloai.core.agent.entity.AgentEvent;
import com.helloai.core.agent.event.AgentEventTraceItem;
import com.helloai.core.agent.event.RecoveryPoint;
import com.helloai.core.agent.mapper.AgentEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 事件流读侧投影测试。
 *
 * <p>验证 {@link AgentEventQueryServiceImpl#traceBySubTaskId} / {@link #traceByRunId} /
 * {@link #traceByTaskId} / {@link #pageAuditByTaskId} 的投影口径（entity → VO 字段一一映射）与顺序透传；
 * 排序本身由 mapper 保证，不在此重复编排。{@code traceByTaskId} 额外验证 runId 规则
 * 收敛在 service 层（ADR-001 §3.1，G-006 消费面易用性增量）。</p>
 *
 * <p>另覆盖 Recovery 消费面 {@link AgentEventQueryServiceImpl#projectRecoveryPoint}：未闭合轮次判定 /
 * 中断点（悬挂工具）/ 已完成工具保序 / 终态事件识别 / 多轮返工只取最后未闭合轮次。</p>
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
        // 期望值口径必须与服务端一致：{@code parseAuditTime} 对「yyyy-MM-dd HH:mm:ss」形态
        // 用 JVM 默认时区装配偏移量（{@code OffsetDateTime.of(local, now().getOffset())}）。
        // 原先硬编码 +08:00，导致本用例在非 +08:00 环境（CI runner = UTC）必然
        // PotentialStubbingProblem 失败（2026-10-08 CI 复现），故改为按同一口径构造。
        OffsetDateTime start = OffsetDateTime.of(
                LocalDateTime.parse("2026-10-01 00:00:00", AUDIT_LOCAL_FORMATTER), currentOffset());
        OffsetDateTime end = OffsetDateTime.of(
                LocalDateTime.parse("2026-10-08 23:59:59", AUDIT_LOCAL_FORMATTER), currentOffset());
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

    // ══════════════════════════════════════════════════════════════════
    //  Recovery 消费面：projectRecoveryPoint（只读恢复点投影）
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Recovery：subTaskId 为空 → 空投影且不触达 mapper")
    void shouldReturnNoneRecoveryWhenSubTaskIdNull() {
        RecoveryPoint point = queryService.projectRecoveryPoint(null);

        assertThat(point.isUnfinished()).isFalse();
        assertThat(point.getCompletedTools()).isEmpty();
        verifyNoInteractions(agentEventMapper);
    }

    @Test
    @DisplayName("Recovery：无任何事件 → 空投影（unfinished=false，各字段为空）")
    void shouldReturnNoneRecoveryWhenNoEvents() {
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of());

        RecoveryPoint point = queryService.projectRecoveryPoint(10L);

        assertThat(point.isUnfinished()).isFalse();
        assertThat(point.getUnfinishedTurn()).isNull();
        assertThat(point.getDanglingTool()).isNull();
        assertThat(point.getLastEventType()).isNull();
        assertThat(point.isTerminalEventSeen()).isFalse();
    }

    @Test
    @DisplayName("Recovery：工具已开始未完成 → unfinished=true 且 danglingTool=该工具（中断点）")
    void shouldDetectDanglingToolAsInterruptPoint() {
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of(
                plainEvent(1L, AgentEventType.AGENT_STARTED.code(), 1, 2),
                toolEvent(2L, AgentEventType.TOOL_CALL_STARTED.code(), 1, "internal_read_attachment")));

        RecoveryPoint point = queryService.projectRecoveryPoint(10L);

        assertThat(point.isUnfinished()).isTrue();
        assertThat(point.getUnfinishedTurn()).isEqualTo(1);
        assertThat(point.getDanglingTool()).isEqualTo("internal_read_attachment");
        assertThat(point.getCompletedTools()).isEmpty();
        assertThat(point.isTerminalEventSeen()).isFalse();
    }

    @Test
    @DisplayName("Recovery：已完成工具保序去重，且仅最后未配对者算中断点")
    void shouldCollectCompletedToolsInOrderAndDetectDangling() {
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of(
                plainEvent(1L, AgentEventType.AGENT_STARTED.code(), 1, 2),
                toolEvent(2L, AgentEventType.TOOL_CALL_STARTED.code(), 1, "tool_a"),
                toolEvent(3L, AgentEventType.TOOL_CALL_COMPLETED.code(), 1, "tool_a"),
                toolEvent(4L, AgentEventType.TOOL_CALL_STARTED.code(), 1, "tool_b"),
                toolEvent(5L, AgentEventType.TOOL_CALL_COMPLETED.code(), 1, "tool_b"),
                toolEvent(6L, AgentEventType.TOOL_CALL_STARTED.code(), 1, "tool_c")));

        RecoveryPoint point = queryService.projectRecoveryPoint(10L);

        assertThat(point.getCompletedTools()).containsExactly("tool_a", "tool_b");
        assertThat(point.getDanglingTool()).isEqualTo("tool_c");
        assertThat(point.getLastEventType()).isEqualTo(AgentEventType.TOOL_CALL_STARTED.code());
        assertThat(point.getLastEventSequence()).isEqualTo(6L);
    }

    @Test
    @DisplayName("Recovery：出现 AGENT_COMPLETED → 该轮闭合，unfinished=false 且零恢复点")
    void shouldMarkClosedWhenAgentCompletedPresent() {
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of(
                plainEvent(1L, AgentEventType.AGENT_STARTED.code(), 1, 2),
                toolEvent(2L, AgentEventType.TOOL_CALL_STARTED.code(), 1, "tool_a"),
                toolEvent(3L, AgentEventType.TOOL_CALL_COMPLETED.code(), 1, "tool_a"),
                plainEvent(4L, AgentEventType.AGENT_COMPLETED.code(), 1, 0)));

        RecoveryPoint point = queryService.projectRecoveryPoint(10L);

        assertThat(point.isUnfinished()).isFalse();
        assertThat(point.getUnfinishedTurn()).isNull();
        assertThat(point.getDanglingTool()).isNull();
        assertThat(point.getCompletedTools()).isEmpty();
        assertThat(point.isTerminalEventSeen()).isTrue();
    }

    @Test
    @DisplayName("Recovery：返工多轮 → 仅最后未闭合轮次计恢复点，上一轮工具事实不计入")
    void shouldOnlyCountLatestUnfinishedTurn() {
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of(
                plainEvent(1L, AgentEventType.AGENT_STARTED.code(), 1, 2),
                toolEvent(2L, AgentEventType.TOOL_CALL_STARTED.code(), 1, "tool_old"),
                toolEvent(3L, AgentEventType.TOOL_CALL_COMPLETED.code(), 1, "tool_old"),
                plainEvent(4L, AgentEventType.AGENT_COMPLETED.code(), 1, 0),
                plainEvent(5L, AgentEventType.AGENT_STARTED.code(), 2, 2),
                toolEvent(6L, AgentEventType.TOOL_CALL_STARTED.code(), 2, "tool_new")));

        RecoveryPoint point = queryService.projectRecoveryPoint(10L);

        assertThat(point.isUnfinished()).isTrue();
        assertThat(point.getUnfinishedTurn()).isEqualTo(2);
        assertThat(point.getDanglingTool()).isEqualTo("tool_new");
        // 第 1 轮已完成工具不属本次恢复范围
        assertThat(point.getCompletedTools()).isEmpty();
        // 终态事件出现过（第 1 轮闭合）——不等于「整体已终结」，故 unfinished 仍为 true
        assertThat(point.isTerminalEventSeen()).isTrue();
    }

    @Test
    @DisplayName("Recovery：RUN_COMPLETED 计入终态事件")
    void shouldTreatRunCompletedAsTerminal() {
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of(
                plainEvent(1L, AgentEventType.AGENT_STARTED.code(), 1, 2),
                plainEvent(2L, AgentEventType.AGENT_COMPLETED.code(), 1, 0),
                plainEvent(3L, AgentEventType.RUN_COMPLETED.code(), 1, 0)));

        RecoveryPoint point = queryService.projectRecoveryPoint(10L);

        assertThat(point.isTerminalEventSeen()).isTrue();
        assertThat(point.isUnfinished()).isFalse();
    }

    @Test
    @DisplayName("Recovery：工具事件缺 tool 名 → 忽略该事件（不产生假中断点）")
    void shouldIgnoreToolEventWithoutToolName() {
        when(agentEventMapper.selectBySubTaskIdOrdered(10L)).thenReturn(List.of(
                plainEvent(1L, AgentEventType.AGENT_STARTED.code(), 1, 2),
                plainEvent(2L, AgentEventType.TOOL_CALL_STARTED.code(), 1, 3)));

        RecoveryPoint point = queryService.projectRecoveryPoint(10L);

        assertThat(point.isUnfinished()).isTrue();
        assertThat(point.getDanglingTool()).isNull();
        assertThat(point.getCompletedTools()).isEmpty();
    }

    /** 非工具事件（payload 无 tool 键）。 */
    private AgentEvent plainEvent(Long id, String eventType, int turn, int step) {
        AgentEvent e = entity(id, "evt-" + id, "run-100-1", turn, step, eventType, OffsetDateTime.now());
        e.setPayload(Map.of("k", "v"));
        return e;
    }

    /** 工具事件（payload 带 tool 键，埋点键见 ChatModelToolLoop）。 */
    private AgentEvent toolEvent(Long id, String eventType, int turn, String tool) {
        AgentEvent e = entity(id, "evt-" + id, "run-100-1", turn, 3, eventType, OffsetDateTime.now());
        e.setPayload(Map.of("tool", tool));
        return e;
    }

    /** 前端 el-date-picker / 数据库写入的本地时间形态（与服务端 parseAuditTime 的兜底格式一致）。 */
    private static final DateTimeFormatter AUDIT_LOCAL_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** JVM 默认时区当前偏移量——与服务端 {@code parseAuditTime} 的装配口径同源。 */
    private static java.time.ZoneOffset currentOffset() {
        return OffsetDateTime.now().getOffset();
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