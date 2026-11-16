package com.helloai.core.system.notification;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 人侧消息推送契约（MSG-1，设计 §3.2）。
 *
 * <p>业务侧只上报<b>事实</b>，文案与深链由消息域集中渲染。两类调用方同一出口
 * {@link NotificationService#publish(NotificationEvent)}：</p>
 * <ul>
 *   <li><b>主通道（自动）</b>：时间线钩子带入 {@link #timelineEventCode} + {@link #instanceKey}
 *       （= task_timeline.id），{@link #type} 留空，由 {@link NotificationMapping} 按事件码解析；</li>
 *   <li><b>例外通道（显式）</b>：不写时间线的事件由该域显式指定 {@link #type} +
 *       {@link #sourceType}/{@link #sourceId}/{@link #instanceKey}。</li>
 * </ul>
 *
 * <p>{@link #payload} 只放渲染所需的<b>数据</b>（条数、原因码、attempt_no），不放句子。</p>
 */
@Getter
@Builder
public class NotificationEvent {

    /** 消息类型；主通道留空（由事件码映射解析），例外通道显式指定。 */
    private final NotificationType type;

    /** 业务对象类型：TASK / SUB_TASK / CONVERSATION / AGENT / RELEASE。 */
    private final String sourceType;

    /** 业务对象 id。 */
    private final Long sourceId;

    /** 事件实例标识：主通道 = task_timeline.id；例外通道取现成单源（设计 §3.3）。 */
    private final String instanceKey;

    /** 时间线事件码；主通道由钩子带入，例外通道为空。 */
    private final String timelineEventCode;

    /** 归属人 sys_user.id；{@code null} = 广播（唯一索引以 COALESCE(receiver_id,0) 收紧）。 */
    private final Long receiverId;

    /** 严重度覆盖；留空则取 {@link NotificationType#getDefaultSeverity()}。 */
    private final Severity severity;

    /** 渲染所需数据（条数 / 原因码 / attempt_no），不放句子。 */
    private final Map<String, Object> payload;
}