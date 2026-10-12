package com.helloai.core.agent.mcp;

import com.helloai.core.agent.runtime.tool.InternalLlmExecutorTools;
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
 *   <li>{@link InternalLlmExecutorTools} —— REF-8.1 内部 LLM 进程内工具通道（读型 + 受控交付登记，
 *       {@code internal_} 前缀；不依赖 MCP 会话鉴权）</li>
 *   <li>{@link ToolCallbackContributor} 实现 —— 各域贡献的平台内置工具（阶段四
 *       联网搜索 Capability 化：planner 域 web_search 经本端口并入，agent 域不反向引用）</li>
 * </ul>
 * </p>
 *
 * <p><b>单 provider 纪律（REF-8.1）</b>：所有 {@code @Tool}（MCP + 内部进程内 + contributor）<b>必须
 * 合并进唯一的 {@link ToolCallbackProvider}</b>——{@link ToolRegistryImpl} / {@code ToolCallbackToolExecutor}
 * 都注入<b>单个</b> {@link ToolCallbackProvider}，若存在两个 provider Bean 会因
 * {@code NoUniqueBeanDefinitionException} 崩溃。内部??工具只<b>追加</b>进 {@code all[]}，
 * 不新增独立 provider，执行 / 注册链路 / MCP tools-list 自动化跟随。</p>
 *
 * <p>⚠️ <b>对外暴露边界（REF-8.1 待评估）</b>：合并进单一 provider 会把 {@code internal_} 工具也
 * 带进外部 MCP {@code tools/list}。若需对外部隐藏，后续应改由 {@code McpToolConfig} 提供
 * <b>聚合 provider</b>（MCP 面只含 {@code McpMcpServer} + contributor，内部面额外并 {@code internal_}），
 * 二者仍合成一个 {@link ToolCallbackProvider} Bean 供执行链路消费。当前采用"可接受暴露"作为
 * 最小改动。</p>
 *
 * <p>§9.3 验收：tools/list 必须返回 helloai-mcp-server 注册的全部工具 schema。</p>
 */
@Configuration
@RequiredArgsConstructor
public class McpToolConfig {

    private final EchoMcpTool echoMcpTool;
    private final McpMcpServer mcpMcpServer;
    /** REF-8.1：内部 LLM 进程内工具通道（读型 + 受控交付登记，internal_ 前缀，无 session 依赖）。 */
    private final InternalLlmExecutorTools internalLlmExecutorTools;
    /** 平台内置工具贡献（各域 +@Tool 实例；空列表时不影响既有 MCP 工具注册）。 */
    private final List<ToolCallbackContributor> toolContributors;

    @Bean
    public ToolCallbackProvider mcpToolCallbacks() {
        Object[] contributed = toolContributors == null ? new Object[0]
                : toolContributors.stream().map(ToolCallbackContributor::toolObject).toArray();
        Object[] all = new Object[3 + contributed.length];
        all[0] = echoMcpTool;
        all[1] = mcpMcpServer;
        all[2] = internalLlmExecutorTools;
        System.arraycopy(contributed, 0, all, 3, contributed.length);
        return MethodToolCallbackProvider.builder()
                .toolObjects(all)
                .build();
    }
}
