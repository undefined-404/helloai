package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.mq.DeadLetterArchiveResponse;
import com.helloai.api.dto.mq.OutboxFailedResponse;
import com.helloai.common.base.R;
import com.helloai.core.agent.entity.AgentCommandOutboxEvent;
import com.helloai.core.agent.service.AgentCommandOutboxService;
import com.helloai.core.dlx.DeadLetterRecoveryService;
import com.helloai.core.dlx.MqDeadLetterArchiveRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AdminMqRecoveryController} A4 S4 单元测试：4 端点转发与返回封装。
 *
 * <p>核心断言点：
 * <ul>
 *   <li>4 端点参数原样透传 core 服务（窗口 / 页码 / includeReplayed / id）；</li>
 *   <li>返回 {@code R} 封装 code=200，分页结构与 DTO 字段映射正确；</li>
 *   <li>控制器无编排：每次调用只触达对应 core 服务一个方法（{@code verifyNoMoreInteractions}）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminMqRecoveryController MQ 人工恢复入口")
class AdminMqRecoveryControllerTest {

    @Mock
    private AgentCommandOutboxService outboxService;

    @Mock
    private DeadLetterRecoveryService deadLetterRecoveryService;

    @InjectMocks
    private AdminMqRecoveryController controller;

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-05T00:00:00+08:00");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-09-05T23:59:59+08:00");

    private AgentCommandOutboxEvent outboxRow(long id) {
        AgentCommandOutboxEvent event = new AgentCommandOutboxEvent();
        event.setId(id);
        event.setEventId("evt-" + id);
        event.setAggregateId("rec-" + id);
        event.setRetryCount(3);
        event.setErrorMsg("broker nack");
        event.setCreateTime(FROM.plusMinutes(id));
        return event;
    }

    private MqDeadLetterArchiveRow deadLetterRow(long id) {
        MqDeadLetterArchiveRow row = new MqDeadLetterArchiveRow();
        row.setId(id);
        row.setOriginalExchange("helloai.execution.command");
        row.setOriginalRoutingKey("execution.command.rk");
        row.setFirstDeathReason("rejected");
        row.setBody("{\"eventId\":\"evt-100\"}");
        row.setCreatedAt(FROM.plusMinutes(id));
        row.setReplayedAt(null);
        return row;
    }

    @Test
    @DisplayName("outbox FAILED 列表：窗口+分页透传，DTO 字段映射正确")
    void shouldListFailedOutbox() {
        Page<AgentCommandOutboxEvent> page = new Page<>(2, 10, 1);
        page.setRecords(List.of(outboxRow(7L)));
        when(outboxService.listFailed(FROM, TO, 2, 10)).thenReturn(page);

        R<PageResult<OutboxFailedResponse>> resp = controller.listFailedOutbox(FROM, TO, 2, 10);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getTotal()).isEqualTo(1);
        assertThat(resp.getData().getCurrent()).isEqualTo(2);
        assertThat(resp.getData().getList()).hasSize(1);
        OutboxFailedResponse row = resp.getData().getList().get(0);
        assertThat(row.getId()).isEqualTo(7L);
        assertThat(row.getEventId()).isEqualTo("evt-7");
        assertThat(row.getAggregateId()).isEqualTo("rec-7");
        assertThat(row.getRetryCount()).isEqualTo(3);
        assertThat(row.getErrorMsg()).isEqualTo("broker nack");
        assertThat(row.getCreateTime()).isEqualTo(FROM.plusMinutes(7));
        verifyNoMoreInteractions(outboxService);
    }

    @Test
    @DisplayName("outbox 重入：id 透传 service，返回 R.ok（无编排）")
    void shouldRequeueOutbox() {
        R<Void> resp = controller.requeueOutbox(42L);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).isNull();
        verify(outboxService).requeueFailed(42L);
        verifyNoMoreInteractions(outboxService);
    }

    @Test
    @DisplayName("死信台账列表：includeReplayed 过滤 + 窗口分页透传，DTO 字段映射正确")
    void shouldListDeadLetters() {
        Page<MqDeadLetterArchiveRow> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(deadLetterRow(9L)));
        when(deadLetterRecoveryService.listArchive(FROM, TO, false, 1, 20)).thenReturn(page);

        R<PageResult<DeadLetterArchiveResponse>> resp =
                controller.listDeadLetters(FROM, TO, false, 1, 20);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().getList()).hasSize(1);
        DeadLetterArchiveResponse row = resp.getData().getList().get(0);
        assertThat(row.getId()).isEqualTo(9L);
        assertThat(row.getOriginalExchange()).isEqualTo("helloai.execution.command");
        assertThat(row.getOriginalRoutingKey()).isEqualTo("execution.command.rk");
        assertThat(row.getFirstDeathReason()).isEqualTo("rejected");
        assertThat(row.getBody()).isEqualTo("{\"eventId\":\"evt-100\"}");
        assertThat(row.getReplayedAt()).isNull();
        verify(deadLetterRecoveryService).listArchive(eq(FROM), eq(TO), eq(false), anyLong(), anyLong());
        verifyNoMoreInteractions(deadLetterRecoveryService);
    }

    @Test
    @DisplayName("死信重放：id 透传 service，返回 R.ok（无编排）")
    void shouldReplayDeadLetter() {
        R<Void> resp = controller.replayDeadLetter(9L);

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData()).isNull();
        verify(deadLetterRecoveryService).replay(9L);
        verifyNoMoreInteractions(deadLetterRecoveryService);
    }
}