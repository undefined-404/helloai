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

    /**
     * 执行时可用工具名清单（Phase 1 Step 2：agent 域消费侧解析 agent_mcp_server
     * 注入，随 AgentContext 透传；与 {@code AgentMcpServerService.getEnabledTools}
     * 同表示，非命令创建方装箱——工具是 agent 域数据，无 §6 跨域问题；
     * null 由 builder 默认值规范化，消费端恒非 null）。
     */
    @Builder.Default
    List<String> tools = List.of();

    /**
     * 执行环境标识（Phase 1 Step 4：remote-agent / local-process）：agent 域消费侧
     * 经 {@code ExecutionEnvironmentProvider.resolve(agent.accessType)} 解析后随
     * {@code AgentContext.environment} 透传（与 tools 同为 agent 域数据直读路径，无 §6 跨域
     * 问题；取 {@code ExecutionEnvironment.name()} 存 String，MQ/Outbox 序列化安全）；
     * 未解析时为 null（消费端 ENVIRONMENT_RESOLVED 埋点按 null 事实记录，不阻断执行链）。
     */
    String environment;
}
