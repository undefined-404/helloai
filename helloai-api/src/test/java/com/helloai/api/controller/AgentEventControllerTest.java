package com.helloai.api.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.event.AgentEventItem;
import com.helloai.common.base.R;
import com.helloai.core.agent.event.AgentEventQueryService;
import com.helloai.core.agent.event.AgentEventTraceItem;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AgentEventController} Replay / Audit 读侧端点单元测试。
 *
 * <p>核心断言：参数原样透传 service；返回 R 封装 code=200 + DTO 字段映射完整；
 * 控制器无编排（verifyNoMoreInteractions）；分页参数最小归一在其控制范围内。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentEventController 事件流读侧端点（Replay / Audit）")
class AgentEventControllerTest {

    @Mock
    private AgentEventQueryService agentEventQueryService;

    @InjectMocks
    private AgentEventController controller;

    private static final Long EVENT_ID = 1001L;
    private static final Long TASK_ID = 2097277905774018561L;
    private static final Long SUB_TASK_ID = 2097277905774018562L;
    private static final Long AGENT_ID = 42L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-08T10:00:00+08:00");

    private AgentEventTraceItem traceItem() {
        return AgentEventTraceItem.builder()
                .id(EVENT_ID)
                .eventId("run-2097277905774018561-1-0-3")
                .runId("run-2097277905774018561-1")
                .taskId(TASK_ID)
                .subTaskId(SUB_TASK_ID)
                .turn(1)
                .step(3)
                .eventType("tool_call_completed")
                .agentId(AGENT_ID)
                .payload(Map.of("toolName", "getAgentStatus"))
                .createTime(NOW)
                .build();
    }

    @Test
    @DisplayName("Replay：runId 透传 + R 封装 + 10 字段映射完整")
    void traceByRunId() {
        when(agentEventQueryService.traceByRunId("run-2097277905774018561-1"))
                .thenReturn(List.of(traceItem()));

        R<List<AgentEventItem>> resp = controller.traceByRunId("run-2097277905774018561-1");

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).hasSize(1);
        AgentEventItem item = resp.getData().get(0);
        assertThat(item.getId()).isEqualTo(EVENT_ID);
        assertThat(item.getEventId()).isEqualTo("run-2097277905774018561-1-0-3");
        assertThat(item.getRunId()).isEqualTo("run-2097277905774018561-1");
        assertThat(item.getTaskId()).isEqualTo(TASK_ID);
        assertThat(item.getSubTaskId()).isEqualTo(SUB_TASK_ID);
        assertThat(item.getTurn()).isEqualTo(1);
        assertThat(item.getStep()).isEqualTo(3);
        assertThat(item.getEventType()).isEqualTo("tool_call_completed");
        assertThat(item.getAgentId()).isEqualTo(AGENT_ID);
        assertThat(item.getPayload()).containsEntry("toolName", "getAgentStatus");
        assertThat(item.getCreateTime()).isEqualTo(NOW);
        verify(agentEventQueryService).traceByRunId("run-2097277905774018561-1");
        verifyNoMoreInteractions(agentEventQueryService);
    }

    @Test
    @DisplayName("Replay：空白 runId 透传短路（service 空列表）→ R 空列表")
    void traceByRunIdBlank() {
        when(agentEventQueryService.traceByRunId("  ")).thenReturn(List.of());

        R<List<AgentEventItem>> resp = controller.traceByRunId("  ");

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).isEmpty();
        verify(agentEventQueryService).traceByRunId("  ");
        verifyNoMoreInteractions(agentEventQueryService);
    }

    @Test
    @DisplayName("Audit：taskId/eventType/page/pageSize 透传 + PageResult 封装")
    void pageAuditByTaskId() {
        Page<AgentEventTraceItem> page = new Page<>(2, 20);
        page.setRecords(List.of(traceItem()));
        page.setTotal(1);
        when(agentEventQueryService.pageAuditByTaskId(TASK_ID, "agent_started", 2L, 20L)).thenReturn(page);

        R<PageResult<AgentEventItem>> resp = controller.pageAuditByTaskId(TASK_ID, "agent_started", 2, 20);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getList()).hasSize(1);
        assertThat(resp.getData().getList().get(0).getEventType()).isEqualTo("tool_call_completed");
        assertThat(resp.getData().getList().get(0).getTurn()).isEqualTo(1);
        assertThat(resp.getData().getTotal()).isEqualTo(1);
        assertThat(resp.getData().getCurrent()).isEqualTo(2);
        assertThat(resp.getData().getPages()).isEqualTo(1);
        verify(agentEventQueryService).pageAuditByTaskId(TASK_ID, "agent_started", 2L, 20L);
        verifyNoMoreInteractions(agentEventQueryService);
    }

    @Test
    @DisplayName("Audit：eventType 空白透传（不过滤语义由 service 决定）")
    void pageAuditByTaskIdBlankEventType() {
        Page<AgentEventTraceItem> page = new Page<>(1, 20);
        page.setRecords(List.of());
        when(agentEventQueryService.pageAuditByTaskId(TASK_ID, "  ", 1L, 20L)).thenReturn(page);

        R<PageResult<AgentEventItem>> resp = controller.pageAuditByTaskId(TASK_ID, "  ", 1, 20);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getList()).isEmpty();
        verify(agentEventQueryService).pageAuditByTaskId(TASK_ID, "  ", 1L, 20L);
        verifyNoMoreInteractions(agentEventQueryService);
    }

    @Test
    @DisplayName("Audit：page 归一下限 1 / pageSize 越界 clamp 100 / 非正回默认 20")
    void pageAuditByTaskIdParamNormalize() {
        Page<AgentEventTraceItem> page = new Page<>(1, 20);
        page.setRecords(List.of());
        when(agentEventQueryService.pageAuditByTaskId(TASK_ID, null, 1L, 100L))
                .thenReturn(page);
        when(agentEventQueryService.pageAuditByTaskId(TASK_ID, null, 1L, 20L))
                .thenReturn(page);
        when(agentEventQueryService.pageAuditByTaskId(TASK_ID, null, 1L, 50L))
                .thenReturn(page);

        controller.pageAuditByTaskId(TASK_ID, null, 0, -1);
        controller.pageAuditByTaskId(TASK_ID, null, 1, 1000);
        controller.pageAuditByTaskId(TASK_ID, null, 0, 50);

        verify(agentEventQueryService).pageAuditByTaskId(TASK_ID, null, 1L, 20L);
        verify(agentEventQueryService).pageAuditByTaskId(TASK_ID, null, 1L, 100L);
        verify(agentEventQueryService).pageAuditByTaskId(TASK_ID, null, 1L, 50L);
        verifyNoMoreInteractions(agentEventQueryService);
    }
}