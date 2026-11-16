package com.helloai.api.support;

import com.helloai.core.shared.event.NotificationCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationEventHub} 单测（MSG-3，设计 §3.6）：按归属过滤 / 广播 / 失效连接清理 / AFTER_COMMIT 契约。
 */
@DisplayName("MSG-3: 消息 SSE 推送按归属过滤与失效清理")
class NotificationEventHubTest {

    @Test
    @DisplayName("receiverId 非空 → 仅推给归属用户的连接")
    void receiverBoundPushesOnlyToOwner() throws Exception {
        NotificationEventHub hub = new NotificationEventHub();
        SseEmitter owner = mock(SseEmitter.class);
        SseEmitter other = mock(SseEmitter.class);
        addEmitter(hub, owner, 1L);
        addEmitter(hub, other, 2L);

        hub.onNotificationCreated(new NotificationCreatedEvent(777L, 1L));

        verify(owner, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(other, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("receiverId 为空（广播）→ 推给全部连接")
    void broadcastPushesToAll() throws Exception {
        NotificationEventHub hub = new NotificationEventHub();
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        addEmitter(hub, first, 1L);
        addEmitter(hub, second, 2L);

        hub.onNotificationCreated(new NotificationCreatedEvent(778L, null));

        verify(first, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("无连接 → 不尝试推送")
    void noEmittersNoAttempt() {
        NotificationEventHub hub = new NotificationEventHub();
        hub.onNotificationCreated(new NotificationCreatedEvent(779L, 1L));
    }

    @Test
    @DisplayName("推送失败（连接已断）→ 移除该连接并 complete")
    void deadEmitterRemovedAndCompleted() throws Exception {
        NotificationEventHub hub = new NotificationEventHub();
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("closed")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        addEmitter(hub, dead, 1L);

        hub.onNotificationCreated(new NotificationCreatedEvent(780L, null));

        verify(dead, times(1)).complete();
        assertThat(emitters(hub)).isEmpty();
    }

    @Test
    @DisplayName("心跳：健康连接收到 ping，失效连接被清理")
    void heartbeatPingsAndCleansDead() throws Exception {
        NotificationEventHub hub = new NotificationEventHub();
        SseEmitter alive = mock(SseEmitter.class);
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("closed")).when(dead).send(any(SseEmitter.SseEventBuilder.class));
        addEmitter(hub, alive, 1L);
        addEmitter(hub, dead, 2L);

        hub.heartbeat();

        verify(alive, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(dead, times(1)).complete();
        assertThat(emitters(hub)).hasSize(1);
    }

    @Test
    @DisplayName("AFTER_COMMIT 契约：监听注解相位必须是事务提交后（防回滚消息被推送）")
    void listenerPhaseIsAfterCommit() throws Exception {
        Method method = NotificationEventHub.class.getMethod("onNotificationCreated", NotificationCreatedEvent.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @SuppressWarnings("unchecked")
    private static List<NotificationEventHub.Emitter> emitters(NotificationEventHub hub) throws Exception {
        Field field = NotificationEventHub.class.getDeclaredField("emitters");
        field.setAccessible(true);
        return (List<NotificationEventHub.Emitter>) field.get(hub);
    }

    private static void addEmitter(NotificationEventHub hub, SseEmitter emitter, Long userId) throws Exception {
        emitters(hub).add(new NotificationEventHub.Emitter(emitter, userId));
    }
}