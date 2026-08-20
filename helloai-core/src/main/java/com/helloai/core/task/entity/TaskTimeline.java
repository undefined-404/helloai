package com.helloai.core.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import com.helloai.common.constant.AgentRole;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 任务事件时间线。
 *
 * <p>用于记录任务生命周期内的关键事件（agent_offline / task_assigned / task_completed 等），
 * 同时承载 AgentHealthCheckTask 的审计日志。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code taskId} / {@code subTaskId} 可空（系统级事件如 agent_offline 不属于具体任务）</li>
 *   <li>{@code eventType} + {@code role} 标识事件类型与产生方角色</li>
 *   <li>{@code payload} 用 JSONB 存储结构化负载（reason / offline_time / metadata 等）</li>
 * </ul>
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_timeline")
public class TaskTimeline extends BaseEntity {

    /** 主任务 ID（系统级事件可空，如 agent_offline） */
    private Long taskId;

    /** 子任务 ID（系统级事件可空，如 agent_offline） */
    private Long subTaskId;

    /** 事件类型：agent_offline / task_assigned / task_completed / ... */
    private String eventType;

    /** 事件产生方角色：PLANNER / EXECUTOR / REVIEWER / SYSTEM */
    private AgentRole role;

    /** 关联 Agent ID（agent_offline 等系统级事件时记录被监控的 Agent） */
    private Long agentId;

    /** 事件负载（JSONB，Map 形式）：结构化记录 reason / offline_time / metadata 等 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;
}