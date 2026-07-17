package com.helloai.core.doorbell;

import com.helloai.common.base.BizException;
import com.helloai.common.config.DoorbellProperties;
import com.helloai.core.observability.HeartbeatService;
import com.helloai.core.service.AgentDutyLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DoorbellService} 单元测试（AgentHub V3 门铃内核 PR-1）。
 *
 * <p>覆盖：建连登记 + 握手、未启用拒绝、响铃尽力而为（未连返回 false、
 * 已连返回 true）、主动断连清理。使用真实 {@link DoorbellRegistry}
 * 与 {@link DoorbellProperties}——二者均为无外部依赖的轻组件。</p>
 *
 * <p>说明：{@link SseEmitter#send} 在 emitter 尚未绑定到请求前会缓冲事件而不抛异常，
 * 故本测试可在无 Web 容器的纯单测环境下验证 connect/ring 的发送路径。</p>
 */
@DisplayName("DoorbellService")
class DoorbellServiceTest {

    private DoorbellProperties properties;
    private DoorbellRegistry registry;
    private AgentDutyLeaseService dutyLeaseService;
    private HeartbeatService heartbeatService;
    private DoorbellService service;

    @BeforeEach
    void setUp() {
        properties = new DoorbellProperties();
        properties.setEnabled(true);
        registry = new DoorbellRegistry();
        dutyLeaseService = mock(AgentDutyLeaseService.class);
        heartbeatService = mock(HeartbeatService.class);
        // 默认在岗，个别用例再单独覆盖为未在岗
        when(dutyLeaseService.isOnDuty(anyLong())).thenReturn(true);
        service = new DoorbellService(properties, registry, dutyLeaseService, heartbeatService);
    }

    @Test
    @DisplayName("建连返回非空连接并登记进注册表")
    void shouldConnectAndRegister() {
        SseEmitter emitter = service.connect(1L);

        assertThat(emitter).isNotNull();
        assertThat(registry.isConnected(1L)).isTrue();
        assertThat(registry.get(1L)).isSameAs(emitter);
        assertThat(service.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("门铃未启用时建连被拒")
    void shouldRejectConnectWhenDisabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.connect(1L))
                .isInstanceOf(BizException.class);
        assertThat(service.connectionCount()).isZero();
    }

    @Test
    @DisplayName("Agent 未在岗（无 ACTIVE 租约）时建连被拒")
    void shouldRejectConnectWhenNotOnDuty() {
        when(dutyLeaseService.isOnDuty(5L)).thenReturn(false);

        assertThatThrownBy(() -> service.connect(5L))
                .isInstanceOf(BizException.class);
        assertThat(registry.isConnected(5L)).isFalse();
        assertThat(service.connectionCount()).isZero();
    }

    @Test
    @DisplayName("向未连门铃的 Agent 响铃返回 false（靠轮询兜底）")
    void shouldReturnFalseWhenRingingUnknownAgent() {
        boolean rung = service.ring(999L, DoorbellSignal.inbox("sub_task.assigned", "sub_task", 66L));

        assertThat(rung).isFalse();
    }

    @Test
    @DisplayName("向已连门铃的 Agent 响铃返回 true")
    void shouldReturnTrueWhenRingingConnectedAgent() {
        service.connect(2L);

        boolean rung = service.ring(2L, DoorbellSignal.inbox("sub_task.assigned", "sub_task", 77L));

        assertThat(rung).isTrue();
    }

    @Test
    @DisplayName("主动断连后连接被清理")
    void shouldDisconnectAndCleanup() {
        service.connect(3L);
        assertThat(registry.isConnected(3L)).isTrue();

        service.disconnect(3L);

        assertThat(registry.isConnected(3L)).isFalse();
        assertThat(service.connectionCount()).isZero();
    }

    @Test
    @DisplayName("断连未连门铃的 Agent 是安全的 no-op")
    void shouldTolerateDisconnectUnknownAgent() {
        service.disconnect(404L);

        assertThat(service.connectionCount()).isZero();
    }

    @Test
    @DisplayName("空注册表下广播保活帧返回 0 且不报错")
    void shouldBroadcastKeepaliveSafelyWhenNoConnection() {
        assertThat(service.broadcastKeepalive()).isZero();
    }

    @Test
    @DisplayName("多条连接广播保活帧逐条送达")
    void shouldBroadcastKeepaliveToAllConnections() {
        service.connect(10L);
        service.connect(11L);

        int sent = service.broadcastKeepalive();

        assertThat(sent).isEqualTo(2);
        assertThat(service.connectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("双心跳开启时建连顺带刷一次 last_seen_at")
    void shouldRefreshHeartbeatOnConnectWhenEnabled() {
        properties.setRefreshHeartbeat(true);

        service.connect(20L);

        verify(heartbeatService, times(1)).seen(20L);
    }

    @Test
    @DisplayName("双心跳默认关时建连不刷心跳")
    void shouldNotRefreshHeartbeatOnConnectByDefault() {
        service.connect(21L);

        verify(heartbeatService, never()).seen(anyLong());
    }

    @Test
    @DisplayName("双心跳刷新失败不阻断建连")
    void shouldTolerateHeartbeatFailureOnConnect() {
        properties.setRefreshHeartbeat(true);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(heartbeatService).seen(anyLong());

        SseEmitter emitter = service.connect(22L);

        assertThat(emitter).isNotNull();
        assertThat(registry.isConnected(22L)).isTrue();
    }
}
