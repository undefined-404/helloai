package com.helloai.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * Agent 收件箱 — 持久化事件通知。
 * 同一 (eventId, agentId) 最多投递一次（联合唯一约束）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_inbox")
public class AgentInbox extends BaseEntity {

    /** 目标 Agent ID */
    private Long agentId;

    /** MQ 事件 ID */
    private String eventId;

    /** 事件类型: sub_task.assigned / review.requested / task.paused */
    private String eventType;

    /** 通知标题 */
    private String title;

    /** 通知摘要 */
    private String summary;

    /** 关联实体类型: sub_task / review / task */
    private String refType;

    /** 关联实体 ID */
    private Long refId;

    /** 是否已读: 0-未读, 1-已读 */
    private Integer isRead;

    /** 是否已归档: 0-否, 1-是 */
    private Integer isArchived;

    /** 阅读时间 */
    private OffsetDateTime readAt;

    /** 优先级: URGENT/HIGH/NORMAL/LOW */
    private String priority;
}
