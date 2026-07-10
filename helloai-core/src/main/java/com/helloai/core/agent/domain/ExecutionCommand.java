package com.helloai.core.agent.domain;

import com.helloai.common.constant.AgentAccessType;
import lombok.Builder;
import lombok.Value;

/**
 * 执行命令载体。
 *
 * <p>当前先作为“调度层发出的最小命令对象”，只承接子任务、目标 Agent、
 * 触发来源与已创建的执行记录标识。后续若接 MQ / Outbox / 独立 Consumer，
 * 统一以该对象为边界继续扩展。</p>
 */
@Value
@Builder
public class ExecutionCommand {

    /** 关联的执行记录 ID，可作为最小命令持久化痕迹。 */
    Long recordId;

    /** 命令事件 ID。 */
    String eventId;

    /** 目标子任务 ID。 */
    Long subTaskId;

    /** 目标 Agent ID。 */
    Long agentId;

    /** 命令触发来源，如 assigned。 */
    String trigger;

    /** 目标 Agent 的接入类型。 */
    AgentAccessType accessType;
}
