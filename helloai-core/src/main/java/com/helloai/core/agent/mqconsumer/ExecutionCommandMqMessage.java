package com.helloai.core.agent.mqconsumer;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.domain.ExecutionCommand;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Phase 2D N6：执行命令的 MQ 序列化载体。
 *
 * <p>{@link ExecutionCommand} 使用 Lombok {@code @Value} 标记，缺少无参构造与 setter，
 * 与 Jackson 反序列化不兼容。本 DTO 以可序列化形式承载同一份字段，
 * MQ 消费端再做 {@link #toDomain()} 转回领域对象。</p>
 *
 * <p>枚举 {@link AgentAccessType} 在消息中以字符串形式落地，避免枚举顺序漂移导致反序列化失败。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionCommandMqMessage {

    /** 关联的执行记录 ID。 */
    private Long recordId;

    /** 命令事件 ID（同时作为消息幂等键）。 */
    private String eventId;

    /** 目标子任务 ID。 */
    private Long subTaskId;

    /** 目标 Agent ID。 */
    private Long agentId;

    /** 命令触发来源，如 {@code assigned / poll-recovery}。 */
    private String trigger;

    /** 目标 Agent 的接入类型（字符串形式落地）。 */
    private String accessType;

    /** 任务声明的技能标签清单（Phase 1 Step 1 fix 装箱透传；老消息缺该字段时 toDomain 按空列表还原）。 */
    private List<String> requiredSkills;

    /**
     * 从领域命令构建 MQ 消息。
     */
    public static ExecutionCommandMqMessage from(ExecutionCommand command) {
        if (command == null) {
            return null;
        }
        return ExecutionCommandMqMessage.builder()
                .recordId(command.getRecordId())
                .eventId(command.getEventId())
                .subTaskId(command.getSubTaskId())
                .agentId(command.getAgentId())
                .trigger(command.getTrigger())
                .accessType(command.getAccessType() != null ? command.getAccessType().name() : null)
                .requiredSkills(command.getRequiredSkills())
                .build();
    }

    /**
     * 转回领域命令。
     */
    public ExecutionCommand toDomain() {
        AgentAccessType access = null;
        if (accessType != null && !accessType.isBlank()) {
            try {
                access = AgentAccessType.valueOf(accessType);
            } catch (IllegalArgumentException ignored) {
                // 未知枚举值按 null 处理，保留向后兼容
                access = null;
            }
        }
        return ExecutionCommand.builder()
                .recordId(recordId)
                .eventId(eventId)
                .subTaskId(subTaskId)
                .agentId(agentId)
                .trigger(trigger)
                .accessType(access)
                // 老消息缺该字段时为 null，显式 null 会覆盖 @Builder.Default，规范化兜底
                .requiredSkills(requiredSkills != null ? requiredSkills : List.of())
                .build();
    }
}
