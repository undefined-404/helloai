package com.helloai.core.agent.service;

import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.event.AgentEventRecorder;

import java.util.List;

/**
 * Turn LLM 调用上下文（P0-B-2）。
 *
 * <p>由 executeOnce 装配层在 LLM 调用点构造：承载 Legacy 单次调用所需的 {@link AgentTask}、
 * Runtime 循环所需的工具清单与事件定位。record 值对象，与 AgentTask 同风格。</p>
 *
 * @param agent         执行 Agent（不可空）
 * @param task          Legacy 单次调用输入（AgentTask：userPrompt + context；不可空）
 * @param tools         启用工具名清单（Runtime 循环可见工具；可空/空 = 无工具）
 * @param runId         Run 标识（事件定位，ADR-001）
 * @param taskId        主任务 ID（可空）
 * @param subTaskId     子任务 ID
 * @param turn          Turn 序号
 * @param eventRecorder 事件记录器（Runtime 循环 TOOL_CALL 事件用；可空）
 */
public record TurnLlmCallContext(Agent agent, AgentTask task, List<String> tools,
        String runId, Long taskId, Long subTaskId, int turn, AgentEventRecorder eventRecorder) {
}
