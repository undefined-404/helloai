package com.helloai.core.shared.doorbell;

import com.helloai.core.shared.event.InboxMessageCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DoorbellRinger} 单元测试（AgentHub 门铃响铃 PR-2）。
 *
 * <p>覆盖：事件字段正确映射为 inbox 信号并响铃；空事件/空 agentId 短路不响铃；
 * 响铃抛异常被吞掉不外抛（旁路不拖累主链路）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DoorbellRinger")
class DoorbellRingerTest {

    @Mock
    private DoorbellService doorbellService;

    @InjectMocks
    private DoorbellRinger ringer;

    @Test
    @DisplayName("收件箱事件映射为 inbox 信号并响铃")
    void shouldRingWithInboxSignal() {
        when(doorbellService.ring(any(), any())).thenReturn(true);
        InboxMessageCreatedEvent event = new InboxMessageCreatedEvent(
                7L, "evt-1", "sub_task.assigned", "sub_task", 66L);

        ringer.onInboxMessageCreated(event);

        ArgumentCaptor<DoorbellSignal> signalCaptor = ArgumentCaptor.forClass(DoorbellSignal.class);
        verify(doorbellService).ring(eq(7L), signalCaptor.capture());
        DoorbellSignal signal = signalCaptor.getValue();
        assertThat(signal.getType()).isEqualTo("inbox");
        assertThat(signal.getEventType()).isEqualTo("sub_task.assigned");
        assertThat(signal.getRefType()).isEqualTo("sub_task");
        assertThat(signal.getRefId()).isEqualTo(66L);
    }

    @Test
    @DisplayName("空事件短路，不响铃")
    void shouldSkipNullEvent() {
        ringer.onInboxMessageCreated(null);

        verify(doorbellService, never()).ring(any(), any());
    }

    @Test
    @DisplayName("agentId 为空短路，不响铃")
    void shouldSkipNullAgentId() {
        InboxMessageCreatedEvent event = new InboxMessageCreatedEvent(
                null, "evt-2", "task.created", "task", 1L);

        ringer.onInboxMessageCreated(event);

        verify(doorbellService, never()).ring(any(), any());
    }

    @Test
    @DisplayName("响铃抛异常被吞掉，不外抛（旁路不拖累主链路）")
    void shouldSwallowRingException() {
        when(doorbellService.ring(any(), any())).thenThrow(new RuntimeException("boom"));
        InboxMessageCreatedEvent event = new InboxMessageCreatedEvent(
                8L, "evt-3", "sub_task.assigned", "sub_task", 99L);

        // 不抛异常即为通过
        ringer.onInboxMessageCreated(event);

        verify(doorbellService).ring(eq(8L), any());
    }
}
