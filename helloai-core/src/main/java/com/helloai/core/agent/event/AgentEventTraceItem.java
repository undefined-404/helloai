package com.helloai.core.agent.event;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Agent 执行轨迹投影（Phase 0 A6 读侧，Event Stream 消费面的只读条目）。
 *
 * <p>由 {@link AgentEventQueryService} 从 {@code agent_event} 投影而来，不暴露
 * {@code AgentEvent} 实体。语义约定：
 * <ul>
 *   <li>{@code step} 为 ADR-001 事件类型槽位（0 = 非 Step 级端点事件），<b>不承担时序</b>；</li>
 *   <li>事件顺序始终以 {@code createTime ASC, id ASC} 为准（append-only 单调）；</li>
 *   <li>{@code payload} 为 JSONB 的只读投影，消费方勿依赖可写语义。</li>
 * </ul>
 * </p>
 */
@Value
@Builder
public class AgentEventTraceItem {

    /** agent_event 主键（雪花 Long，全局唯一）；供合并时间线的前端 key 与排序标识。 */
    Long id;

    String eventId;

    String runId;

    Long taskId;

    Long subTaskId;

    Integer turn;

    Integer step;

    String eventType;

    Long agentId;

    Map<String, Object> payload;

    OffsetDateTime createTime;
}