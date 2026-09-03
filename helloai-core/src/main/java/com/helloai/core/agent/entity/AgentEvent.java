package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * Agent 事件轨迹实体（{@code agent_event} 表，Phase 0 B1）。
 *
 * <p>append-only：只插入、不更新（无状态机）；与 {@code agent_outbox_event}
 * 共享同一 {@code eventId}，供 {@code EventReconciliationService}（B3）对账。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_event")
public class AgentEvent extends BaseEntity {

    /** 事件唯一 ID（与 agent_outbox_event.event_id 共享，B3 对账键）。 */
    private String eventId;

    /** Run 标识（run-{taskId}-{roundNum}，见 ADR-001）。 */
    private String runId;

    private Long taskId;

    private Long subTaskId;

    /** Turn 序号（一次 Agent 完整工作周期，从 1 起）。 */
    private Integer turn;

    /** Turn 内原子动作序号（从 0 起）；0 表示非 Step 级事件。 */
    private Integer step;

    /** 事件类型（AgentEventType.code()，snake_case）。 */
    private String eventType;

    private Long agentId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;
}