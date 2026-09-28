package com.helloai.core.agent.mcp;

import com.helloai.core.agent.tool.ToolCallbackContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * spring-ai 1.0 GA / 1.1 MCP Server 工具注册配置。
 *
 * <p>关键发现：spring-ai 1.0 GA 的 {@code @Tool} 注解方法<b>不会</b>被自动注册为
 * {@code ToolCallback}。必须显式声明 {@link MethodToolCallbackProvider} Bean，
 * spring-ai MCP Server 自动配置才会把它收集并暴露为 MCP 工具。</p>
 *
 * <p>补充：spring-ai 1.1 的 {@code @McpTool} 注解在 AOP 代理下（如
 * {@code @Transactional}、{@code @Cacheable}）会被静默忽略——
 * {@code StatelessServerSpecificationFactoryAutoConfiguration} 用
 * {@code method.isAnnotationPresent(McpTool.class)} 判断，代理类上找不到注解。
 * 本配置通过 {@code MethodToolCallbackProvider.builder().toolObjects(...)} 传
 * <b>原始对象</b>（不是 Spring 代理），绕过此坑。</p>
 *
 * <p>反编译 {@code spring-ai-model-1.0.0.jar} 确认存在：
 * <ul>
 *   <li>{@code org.springframework.ai.tool.method.MethodToolCallbackProvider}</li>
 *   <li>{@code org.springframework.ai.tool.method.MethodToolCallbackProvider.Builder}</li>
 * </ul>
 * </p>
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>{@link EchoMcpTool} —— 连通性诊断工具（1 个 @Tool）</li>
 *   <li>{@link McpMcpServer} —— §9.1 协议 6 工具 + getAgentStatus（7 个 @Tool）</li>
 *   <li>{@link ToolCallbackContributor} 实现 —— 各域贡献的平台内置工具（阶段四
 *       联网搜索 Capability 化：planner 域 web_search 经本端口并入，agent 域不反向引用）</li>
 * </ul>
 * </p>
 *
 * <p>§9.3 验收：tools/list 必须返回 helloai-mcp-server 注册的全部 8 个工具 schema。</p>
 */
@Configuration
@RequiredArgsConstructor
public class McpToolConfig {

    private final EchoMcpTool echoMcpTool;
    private final McpMcpServer mcpMcpServer;
    /** 平台内置工具贡献（各域 +@Tool 实例；空列表时不影响既有 MCP 工具注册）。 */
    private final List<ToolCallbackContributor> toolContributors;

    @Bean
    public ToolCallbackProvider mcpToolCallbacks() {
        Object[] contributed = toolContributors == null ? new Object[0]
                : toolContributors.stream().map(ToolCallbackContributor::toolObject).toArray();
        Object[] all = new Object[2 + contributed.length];
        all[0] = echoMcpTool;
        all[1] = mcpMcpServer;
        System.arraycopy(contributed, 0, all, 2, contributed.length);
        return MethodToolCallbackProvider.builder()
                .toolObjects(all)
                .build();
    }
}
