package com.helloai.core.agent.tool;

/**
 * 平台工具注册元数据（Phase 1 Step 2，长期思路 P0-1 ToolRegistry 最小形态）。
 *
 * <p>当前只承载工具标识与描述——平台当前工具均为 MCP 工具（McpMcpServer 11 个 @Tool +
 * EchoMcpTool 1 个），元数据来源 = spring-ai {@code ToolCallbackProvider} 收集的 @Tool
 * 注解（单一事实源，零漂移）。参数 schema / 频率限制等扩展元数据留待长期思路
 * P1 Capability System 阶段按需补齐，本轮不臆造未存在字段。</p>
 */
public record ToolDefinition(String name, String description) {

    public ToolDefinition {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
    }
}
