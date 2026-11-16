package com.helloai.api.support;

import com.helloai.core.shared.event.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 人侧站内消息推送（MSG-3，设计 §3.6）。镜像 {@link WorkbenchEventHub} 的连接表 / 心跳 / 按归属过滤，
 * 两处不同：
 * <ul>
 *   <li>监听 {@link NotificationCreatedEvent} 用 {@code @TransactionalEventListener(AFTER_COMMIT)}——
 *       消息成功落库且事务提交后才推，避免推了一条已回滚的消息（设计 §3.6）；</li>
 *   <li>帧体只带 {@code notificationId}，正文一律回源查库——漏推 / 重复推只影响提示时机，不影响数据。</li>
 * </ul>
 *
 * <p><b>按归属过滤</b>：连接建立时绑定当前 userId；{@code receiverId} 非空时仅推给归属用户的连接，
 * 为空（广播，仅公告类例外通道）时推给全部连接。</p>
 *
 * <p><b>边界</b>：连接是进程内的。多副本部署时事件只在发布实例上广播，连到其他副本的客户端会漏掉
 * 该次提示；本通道定位为体验增强，不承担跨副本一致性，数据靠未读列表补齐（设计 §4.1）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventHub {

    /** 30 分钟：前端断开后退避重连，避免长连接被网关静默回收后永远收不到推送。 */
    private static final long EMITTER_TIMEOUT_MS = 1_800_000L;

    private final List<Emitter> emitters = new CopyOnWriteArrayList<>();

    /** 连接 + 归属用户（null = 未识别用户，只接收广播事件）。 */
    record Emitter(SseEmitter emitter, Long userId) {
    }

    /** 建立一条消息推送连接；userId 由请求线程解析后传入，用于按归属过滤。 */
    public SseEmitter connect(Long userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Emitter registration = new Emitter(emitter, userId);
        emitters.add(registration);
        Runnable remove = () -> emitters.remove(registration);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(e -> remove.run());
        if (!send(emitter, "connected", "ok")) {
            emitters.remove(registration);
            emitter.complete();
        }
        return emitter;
    }

    /** 消息落库且事务提交后 → 推一帧「新消息到了」，前端收到后重拉未读 / 列表。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        if (emitters.isEmpty()) {
            return;
        }
        String data = String.valueOf(event.getNotificationId());
        Long receiverId = event.getReceiverId();
        for (Emitter registration : emitters) {
            // receiverId 非空 → 仅推给归属用户；为空（广播）→ 推给全部连接
            if (receiverId != null && !Objects.equals(receiverId, registration.userId())) {
                continue;
            }
            if (!send(registration.emitter(), "notification", data)) {
                emitters.remove(registration);
                registration.emitter().complete();
            }
        }
    }

    /** 心跳：25s 一次，防止代理把空闲连接掐掉；顺带清理已失效连接。前端直接忽略 ping 帧。 */
    @Scheduled(fixedDelay = 25_000L)
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        for (Emitter registration : emitters) {
            if (!send(registration.emitter(), "ping", "1")) {
                emitters.remove(registration);
                registration.emitter().complete();
            }
        }
    }

    private static boolean send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("消息推送连接已失效: event={}, err={}", event, e.getMessage());
            return false;
        }
    }
}