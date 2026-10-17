package com.helloai.core.agent.mcp;

import com.helloai.core.agent.runtime.tool.InternalLlmExecutorTools;
import com.helloai.core.agent.tool.InternalToolCallbacks;
import com.helloai.core.agent.tool.ToolCallbackContributor;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
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
 * <p><b>对外面（{@link #mcpToolCallbacks()}）覆盖范围</b>：
 * <ul>
 *   <li>{@link EchoMcpTool} —— 连通性诊断工具（1 个 @Tool）</li>
 *   <li>{@link McpMcpServer} —— §9.1 协议 6 工具 + getAgentStatus（7 个 @Tool）</li>
 *   <li>{@link ToolCallbackContributor} 实现 —— 各域贡献的平台内置工具（阶段四
 *       联网搜索 Capability 化：planner 域 web_search 经本端口并入，agent 域不反向引用）</li>
 * </ul>
 * </p>
 *
 * <p><b>单 provider 纪律（REF-8.1）</b>：{@link ToolRegistryImpl} / {@code ToolCallbackToolExecutor}
 * 都注入<b>单个</b> {@link ToolCallbackProvider}，若存在两个 provider Bean 会因
 * {@code NoUniqueBeanDefinitionException} 崩溃。故对外 MCP 面仍是<b>唯一</b> provider Bean。</p>
 *
 * <p><b>★ 内部工具不进对外面（REF-8.1 B，2026-10-12）</b>：{@link InternalLlmExecutorTools}
 * （{@code internal_} 前缀的进程内工具）<b>不再</b>并入 {@link #mcpToolCallbacks()}，改由
 * {@link #internalToolCallbacks()}（类型 {@link InternalToolCallbacks}，<b>不是</b>
 * {@code ToolCallbackProvider}）承载，使 spring-ai MCP Server 自动配置
 * （{@code ToolCallbackConverterAutoConfiguration#aggregateToolCallbacks} 聚合<b>全部</b>
 * provider Bean）<b>收集不到</b>它们 ⇒ 对外 {@code tools/list} 逐字回到 REF-8.1 之前的集合，
 * 满足借鉴落地 §10.5 红线④「不污染既有工具面与外部 agent 契约」。
 * 运行时经 {@link InternalToolCallbacks#union} 合成唯一目录（对外面 ∪ 内部面）供消费。</p>
 *
 * <p>§9.3 验收：tools/list 必须返回 helloai-mcp-server 注册的全部工具 schema
 * （= 上列「对外面」覆盖范围，不含 {@code internal_}）。</p>
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

    /**
     * 对外 MCP 工具面——spring-ai MCP Server 自动配置收集的<b>唯一</b> {@link ToolCallbackProvider}。
     *
     * <p>只含 echo + {@link McpMcpServer} + 各域 contributor；内部执行链专用工具（{@code internal_}）
     * 见 {@link #internalToolCallbacks()}，<b>不</b>在此列（REF-8.1 B）。</p>
     */
    @Bean
    public ToolCallbackProvider mcpToolCallbacks() {
        Object[] contributed = toolContributors == null ? new Object[0]
                : toolContributors.stream()
                        .map(ToolCallbackContributor::toolObject)
                        .filter(java.util.Objects::nonNull)
                        .toArray();
        Object[] all = new Object[2 + contributed.length];
        all[0] = echoMcpTool;
        all[1] = mcpMcpServer;
        System.arraycopy(contributed, 0, all, 2, contributed.length);
        return MethodToolCallbackProvider.builder()
                .toolObjects(all)
                .build();
    }

    /**
     * 内部执行链专用工具回调（{@code internal_} 前缀）——REF-8.1 B。
     *
     * <p>类型为 {@link InternalToolCallbacks}（<b>不是</b> {@link ToolCallbackProvider}），
     * 故不被 spring-ai MCP Server 自动配置收集 ⇒ 不进对外 {@code tools/list}；
     * 运行时经 {@link InternalToolCallbacks#union} 与对外面合成唯一目录消费。</p>
     */
    @Bean
    public InternalToolCallbacks internalToolCallbacks() {
        ToolCallback[] internal = MethodToolCallbackProvider.builder()
                .toolObjects(internalLlmExecutorTools)
                .build()
                .getToolCallbacks();
        return new InternalToolCallbacks(internal);
    }
}
