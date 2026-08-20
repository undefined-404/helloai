package com.helloai.core.shared.doorbell;

import com.helloai.common.config.DoorbellProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DoorbellKeepaliveTask} 单元测试（AgentHub 门铃 PR-4）。
 *
 * <p>覆盖：门铃关闭时跳过、无连接时跳过、有连接时广播一次、广播异常被吞不外抛。
 * 用真实 {@link DoorbellProperties} + mock {@link DoorbellService}，只验证调度触发逻辑，
 * 不重复验证广播本身（那由 {@code DoorbellServiceTest} 覆盖）。</p>
 */
@DisplayName("DoorbellKeepaliveTask")
class DoorbellKeepaliveTaskTest {

    private DoorbellProperties properties;
    private DoorbellService doorbellService;
    private DoorbellKeepaliveTask task;

    @BeforeEach
    void setUp() {
        properties = new DoorbellProperties();
        properties.setEnabled(true);
        doorbellService = mock(DoorbellService.class, RETURNS_DEFAULTS);
        task = new DoorbellKeepaliveTask(properties, doorbellService);
    }

    @Test
    @DisplayName("门铃通道关闭时不广播")
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);

        task.keepalive();

        verify(doorbellService, never()).broadcastKeepalive();
    }

    @Test
    @DisplayName("当前无连接时不广播")
    void shouldSkipWhenNoConnection() {
        when(doorbellService.connectionCount()).thenReturn(0);

        task.keepalive();

        verify(doorbellService, never()).broadcastKeepalive();
    }

    @Test
    @DisplayName("有连接时广播一次保活帧")
    void shouldBroadcastWhenConnected() {
        when(doorbellService.connectionCount()).thenReturn(2);
        when(doorbellService.broadcastKeepalive()).thenReturn(2);

        task.keepalive();

        verify(doorbellService, times(1)).broadcastKeepalive();
    }

    @Test
    @DisplayName("广播抛异常时被吞掉，不冒泡打断调度线程")
    void shouldSwallowBroadcastException() {
        when(doorbellService.connectionCount()).thenReturn(1);
        when(doorbellService.broadcastKeepalive()).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> task.keepalive()).doesNotThrowAnyException();
    }
}
