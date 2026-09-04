package com.helloai.core.agent.domain;

import com.helloai.common.constant.AgentAccessType;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * 执行命令载体。
 *
 * <p>当前先作为“调度层发出的最小命令对象”，只承接子任务、目标 Agent、
 * 触发来源与已创建的执行记录标识。后续若接 MQ / Outbox / 独立 Consumer，
 * 统一以该对象为边界继续扩展。</p>
 *
 * <p>Phase 1 Step 1 fix（LOG-20260904-009）：新增 {@link #requiredSkills}——
 * task 域 → agent 域装箱传入的技能标签（§6 依赖方向红线：agent 域不反向查询 task，
 * 由调用方在创建命令时从 task 数据装箱；null 由 builder 默认值规范化为空列表）。</p>
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

    /**
     * 任务声明的技能标签清单（task → agent 装箱传入，与 task.requiredSkills 同表示；
     * null 由 builder 默认值规范化，消费端恒非 null）。
     */
    @Builder.Default
    List<String> requiredSkills = List.of();
}
