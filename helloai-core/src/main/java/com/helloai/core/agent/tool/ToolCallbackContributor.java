package com.helloai.core.agent.tool;

/**
 * 平台内置工具贡献端口（阶段四：联网搜索 Capability 化）。
 *
 * <p>工具注册点（{@code McpToolConfig.mcpToolCallbacks}）在 agent 域，而内置工具的
 * 业务能力归属各域（如联网搜索归 planner，§5.3）——本端口反转依赖方向
 * （§7.2 Port 反转）：各域实现本接口返回含 {@code @Tool} 方法的实例，
 * agent 域收集器（{@code McpToolConfig}）统一并入 spring-ai {@code ToolCallbackProvider}，
 * 平台工具目录（{@link ToolRegistry}）与执行回路（{@link ToolExecutor}）自动可见，
 * 不另建平行 Registry（§50.7）。</p>
 *
 * <p>实现必须是<b>原始对象</b>（非 Spring AOP 代理）：代理类上找不到 {@code @Tool}
 * 方法注解（与 {@code McpToolConfig} 的 McpTool 代理坑同源），方法内不得使用
 * {@code @Transactional}/{@code @Cacheable} 等触发代理的注解。</p>
 */
public interface ToolCallbackContributor {

    /**
     * 返回承载一个或多个 {@code @Tool} 方法的实例（通常为 this）。
     */
    Object toolObject();
}