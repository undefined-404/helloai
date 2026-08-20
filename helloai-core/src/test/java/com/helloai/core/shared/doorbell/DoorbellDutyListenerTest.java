package com.helloai.core.shared.doorbell;

import com.helloai.core.shared.event.DutyLeaseClosedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link DoorbellDutyListener} 单元测试（AgentHub 门铃 PR-3）。
 *
 * <p>覆盖：值班关闭事件触发按 agentId 主动断门铃；空事件 / 空 agentId 短路不断连；
 * 断连抛异常被吞掉不外抛（离岗断门铃无副作用，不拖累巡检 / 签退主链路）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DoorbellDutyListener")
class DoorbellDutyListenerTest {

    @Mock
    private DoorbellService doorbellService;

    @InjectMocks
    private DoorbellDutyListener listener;

    @Test
    @DisplayName("值班关闭事件触发对应 Agent 主动断门铃")
    void shouldDisconnectOnDutyLeaseClosed() {
        listener.onDutyLeaseClosed(new DutyLeaseClosedEvent(7L, "manual_close"));

        verify(doorbellService).disconnect(eq(7L));
    }

    @Test
    @DisplayName("空事件短路，不断连")
    void shouldSkipNullEvent() {
        listener.onDutyLeaseClosed(null);

        verify(doorbellService, never()).disconnect(anyLong());
    }

    @Test
    @DisplayName("agentId 为空短路，不断连")
    void shouldSkipNullAgentId() {
        listener.onDutyLeaseClosed(new DutyLeaseClosedEvent(null, "lease_expired"));

        verify(doorbellService, never()).disconnect(anyLong());
    }

    @Test
    @DisplayName("断连抛异常被吞掉，不外抛（离岗断门铃无副作用）")
    void shouldSwallowDisconnectException() {
        doThrow(new RuntimeException("boom")).when(doorbellService).disconnect(anyLong());

        // 不抛异常即为通过
        listener.onDutyLeaseClosed(new DutyLeaseClosedEvent(9L, "lease_expired"));

        verify(doorbellService).disconnect(eq(9L));
    }
}
