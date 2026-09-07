package com.helloai.core.agent.runtime.sandbox;

import com.helloai.common.constant.AgentAccessType;

/**
 * 沙箱解析上下文（P0-C Phase 4）。
 *
 * @param accessType Agent 接入类型（决定执行环境解析，不可空）
 * @param taskId     主任务 ID（可空：非任务级解析场景）
 * @param subTaskId  子任务 ID（可空）
 * @param agentId    执行 Agent ID（可空）
 */
public record SandboxContext(AgentAccessType accessType, Long taskId, Long subTaskId, Long agentId) {
}
