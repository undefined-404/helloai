package com.helloai.core.system.notification;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.UserNotification;

/**
 * 人侧站内消息服务（MSG-1，设计 §3.2）。
 *
 * <p>单一出口 {@link #publish}：业务侧只上报事实，文案/深链/幂等/折叠由消息域收口。
 * 读取按归属人过滤（owner-scoped 布尔已读）；广播式「全员重新可见」由公告对象的版本已读承载
 * （设计 §3.1 二分），不在本表做布尔广播。</p>
 *
 * <p><b>平台开关</b>：{@code notification.enabled} 默认 OFF。OFF 时 {@link #publish} 首行早退
 * 不写库，读取端点返回空（停发 + 隐藏，设计 §3.10）。</p>
 */
public interface NotificationService extends IService<UserNotification> {

    /**
     * 推送一条人侧消息。幂等：同 dedupe_key 重复调用不产生第二行。
     *
     * <p>开关 OFF / 未登记事件码 / SILENT 形态一律静默不发。落库成功后发
     * {@code NotificationCreatedEvent}（AFTER_COMMIT 驱动 SSE），幂等跳过不发。</p>
     */
    void publish(NotificationEvent event);

    /**
     * 归属人消息分页（未读优先，可按类型筛）。开关 OFF 返回空页。
     *
     * @param type 消息类型名过滤；{@code null} = 不限
     */
    IPage<UserNotification> pageByReceiver(Long receiverId, String type, int page, int pageSize);

    /** 归属人未读数（铃铛角标的消息部分；公告未读由控制层合并）。开关 OFF 返回 0。 */
    long unreadCount(Long receiverId);

    /** 标记已读（归属校验 + 幂等）。 */
    void markRead(Long receiverId, Long notificationId);

    /** 全部已读（只影响当前归属人）。 */
    void markAllRead(Long receiverId);

    /** 软归档（归属校验 + 幂等）。 */
    void archive(Long receiverId, Long notificationId);
}