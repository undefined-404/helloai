package com.helloai.core.agent.tool;

/**
 * 工具执行结果（P0-C Phase 2）。
 *
 * <p>由 {@link ToolExecutor} 返回的不可变执行结果；成功时携带工具原始输出，
 * 失败时携带错误消息，二者互斥（success=true 时 errorMessage 恒 null）。</p>
 *
 * @param toolName     执行的工具名
 * @param success      是否成功（未知工具 / 空参 / 执行异常均为 false）
 * @param output       成功时的工具原始输出（JSON 字符串，可为 null）
 * @param errorMessage 失败时的错误消息（成功时为 null）
 */
public record ToolExecutionResult(String toolName, boolean success, String output, String errorMessage) {

    public static ToolExecutionResult success(String toolName, String output) {
        return new ToolExecutionResult(toolName, true, output, null);
    }

    public static ToolExecutionResult failure(String toolName, String errorMessage) {
        return new ToolExecutionResult(toolName, false, null, errorMessage);
    }
}
