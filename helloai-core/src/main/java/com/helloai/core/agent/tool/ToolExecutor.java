package com.helloai.core.agent.tool;

/**
 * 平台工具执行回路（P0-C Phase 2）。
 *
 * <p>与 {@link ToolRegistry}（元数据面：认识工具）构成执行对偶——本接口负责按工具名
 * 程序化调用已注册的平台 MCP 工具（spring-ai {@code ToolCallback} 真身），是 AgentRuntime
 * 八件套成员之一，供 Phase 3 AgentLoop（Runtime 真身内的工具调用循环）与诊断路径消费。</p>
 *
 * <p>契约：未知工具 / 空参 / 执行异常均以 {@link ToolExecutionResult#success()}=false
 * + {@code errorMessage} 表达，不抛异常（best-effort 不阻断调用方，与 ToolRegistry
 * resolve 的防御口径一致）。</p>
 */
public interface ToolExecutor {

    /**
     * 按名称执行平台工具。
     *
     * @param toolName      工具名（ToolCallback name，不可空/空白）
     * @param argumentsJson 工具入参 JSON 字符串（可为 null/空，按 {@code "{}"} 处理）；
     *                      业务 MCP 工具所需 {@code agentId / _sessionId} 等 @ToolParam
     *                      必须由调用方显式传入（鉴权透传口径，见 McpAuthContext）
     * @return 执行结果（永不为 null）
     */
    ToolExecutionResult execute(String toolName, String argumentsJson);
}
