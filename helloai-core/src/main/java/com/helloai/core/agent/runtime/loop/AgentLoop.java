package com.helloai.core.agent.runtime.loop;

/**
 * Agent 执行循环（P0-C Phase 3，AgentRuntime 八件套成员之一）。
 *
 * <p>负责一次 Agent Turn 的完整循环：Model → 工具调用 → Observation → ... → 终态文本。
 * 循环内工具执行走 {@link com.helloai.core.agent.tool.ToolExecutor}（Phase 2 执行回路），
 * 每次调用按 ADR-001 记录 TOOL_CALL_STARTED / TOOL_CALL_COMPLETED 事件（write-only）。</p>
 *
 * <p>与 Runtime 边界一致：不依赖 Planner / Scheduler / Reviewer / Task Service；
 * provider 无关（只依赖 spring-ai ChatModel 契约），具体模型行为由调用方注入。</p>
 *
 * <p>第一阶段不要求一次完成完整 Tool Loop（design/Agent_Runtime.md）——单轮无工具调用
 * 即正常终态；工具循环由实现按 maxIterations 硬上限保证终止。</p>
 */
public interface AgentLoop {

    /**
     * 执行一次 Agent Turn 循环。
     *
     * @param input 循环输入（chatModel / toolExecutor 不可空，缺失时返回 ERROR 结果）
     * @return 循环结果（永不为 null）
     */
    AgentLoopResult run(AgentLoopInput input);
}
