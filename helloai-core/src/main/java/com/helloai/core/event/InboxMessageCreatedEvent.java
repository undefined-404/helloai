package com.helloai.core.event;

import lombok.Getter;

/**
 * 收件箱消息已创建事件（AgentHub V3 门铃响铃 PR-2）。
 *
 * <p>由 {@code AgentInboxService.send()} 在收件箱记录<b>成功落库后</b>发布
 * （幂等跳过的重复投递不发），用于在事务提交后异步驱动门铃响铃，
 * 把 Agent 的感知延迟从 30 秒轮询级降到秒级。</p>
 *
 * <p>三条通知路径（TaskController 直发 / SubTaskService 状态流转 / MQ NotificationConsumer）
 * 都收口于 {@code send()}，故在此一处发事件即可覆盖全部，无需逐路径接线
 * （见 {@code doc/HelloAI_门铃通知通道设计.md} §7）。</p>
 *
 * <p>事件只携带响铃所需的最小字段——门铃只送"有事了"的轻量信号，不含正文，
 * Agent 收到后自行走 MCP {@code pullTasks} 取内容。</p>
 */
@Getter
public class InboxMessageCreatedEvent {

    /** 目标 Agent ID。 */
    private final Long agentId;

    /** 事件幂等 ID（仅用于日志与诊断）。 */
    private final String eventId;

    /** 事件类型，如 {@code sub_task.assigned}。 */
    private final String eventType;

    /** 关联实体类型，如 {@code sub_task}。 */
    private final String refType;

    /** 关联实体 ID。 */
    private final Long refId;

    public InboxMessageCreatedEvent(Long agentId, String eventId, String eventType,
                                    String refType, Long refId) {
        this.agentId = agentId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.refType = refType;
        this.refId = refId;
    }
}
