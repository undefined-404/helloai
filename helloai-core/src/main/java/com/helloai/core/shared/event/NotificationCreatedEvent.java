package com.helloai.core.shared.event;

import lombok.Getter;

/**
 * 人侧消息已创建事件（MSG-3，设计 §3.6）。
 *
 * <p>由 {@code NotificationService.publish()} 在消息<b>成功落库后</b>发布（幂等跳过的重复投递不发），
 * 供 {@code NotificationEventHub} 在 {@code AFTER_COMMIT} 推 SSE 信令。帧体只携带 notificationId，
 * 正文一律回源查库——漏推/重复推只影响提示时机，不影响数据。</p>
 */
@Getter
public class NotificationCreatedEvent {

    /** 新落库的消息 id。 */
    private final Long notificationId;

    /** 归属人 sys_user.id；{@code null} = 广播。 */
    private final Long receiverId;

    public NotificationCreatedEvent(Long notificationId, Long receiverId) {
        this.notificationId = notificationId;
        this.receiverId = receiverId;
    }
}