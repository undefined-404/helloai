package com.helloai.api.dto.subtask;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 子任务执行时间线条目（ 派发控制台）。
 *
 * <p>用于 GET /api/sub-tasks/{id}/timeline 返回结构，按 id 升序排列。
 * 字段命名遵循字段命名规范；role 由实体 {@code AgentRole} 枚举转为字符串，
 * 避免 Controller 出现枚举序列化逻辑。</p>
 *
 * <p>所属端点：
 * <ul>
 *   <li>GET /api/sub-tasks/{id}/timeline</li>
 * </ul>
 * </p>
 */
@Data
public class TaskTimelineItem {

    /** 时间线条目 ID（雪花 Long） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 事件类型：agent_offline / task_assigned / task_completed 等 */
    private String eventType;

    /** 事件产生方角色名（PLANNER / EXECUTOR / REVIEWER / SYSTEM），可能为空 */
    private String role;

    /** 关联 Agent ID，可能为空（系统级事件） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long agentId;

    /** 事件负载（JSONB Map），前端折叠展示 */
    private Map<String, Object> payload;

    /** 创建时间戳 */
    private OffsetDateTime createTime;
}