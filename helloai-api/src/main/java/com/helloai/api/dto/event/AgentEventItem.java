package com.helloai.api.dto.event;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Agent 执行轨迹条目（Replay / Audit 读侧暴露）。
 *
 * <p>用于 GET /api/agent-events/traceByRunId/{runId} 与
 * GET /api/agent-events/pageAuditByTaskId/{taskId} 返回结构，字段对齐 core 投影
 * {@code AgentEventTraceItem}；主键与关联 ID 序列化为字符串，避免前端 JS Number
 * 精度丢失（LongId 规范）。</p>
 *
 * <p>语义约定（继承 core 投影）：
 * <ul>
 *   <li>{@code step} 为 ADR-001 事件类型槽位（0 = 非 Step 级端点事件），不承担时序；</li>
 *   <li>事件顺序始终以 {@code createTime ASC, id ASC} 为准（append-only 单调）；</li>
 *   <li>{@code payload} 为 JSONB 的只读投影，消费方勿依赖可写语义。</li>
 * </ul>
 * </p>
 */
@Data
public class AgentEventItem {

    /** agent_event 主键（雪花 Long，全局唯一）；供前端 key 与排序标识 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 事件唯一标识（runId-turn-step 组合） */
    private String eventId;

    /** Run 标识（run-{taskId}-{roundNum}，见 ADR-001） */
    private String runId;

    /** 主任务 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /** 子任务 ID（非子任务维度事件时为空） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long subTaskId;

    /** Turn 序号（从 1 起） */
    private Integer turn;

    /** 事件类型槽位（0 = 非 Step 级端点事件，见 ADR-001） */
    private Integer step;

    /** 事件类型（agent_started / skill_resolved / tool_call_completed 等） */
    private String eventType;

    /** 关联 Agent ID，可能为空（系统级事件） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long agentId;

    /** 事件负载（JSONB Map），只读投影 */
    private Map<String, Object> payload;

    /** 事件时间戳 */
    private OffsetDateTime createTime;
}