package com.helloai.core.agent.tool;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 平台内置工具贡献端口（阶段四：联网搜索 Capability 化）。
 *
 * <p>工具注册点（{@code McpToolConfig.mcpToolCallbacks}）在 agent 域，而内置工具的
 * 业务能力归属各域（如联网搜索归 planner，§5.3）——本端口反转依赖方向
 * （§7.2 Port 反转）：各域实现本接口返回含 {@code @Tool} 方法的实例，
 * agent 域收集器（{@code McpToolConfig}）统一并入 spring-ai {@code ToolCallbackProvider}，
 * 平台工具目录（{@link ToolRegistry}）与执行回路（{@code ToolExecutor}）自动可见，
 * 不另建平行 Registry（§50.7）。</p>
 *
 * <p>实现必须是<b>原始对象</b>（非 Spring AOP 代理）：代理类上找不到 {@code @Tool}
 * 方法注解（与 {@code McpToolConfig} 的 McpTool 代理坑同源），方法内不得使用
 * {@code @Transactional}/{@code @Cacheable} 等触发代理的注解。</p>
 *
 * <p><b>REF-1.3 增补</b>：本端口同时是「注册层」的声明面——各域可就自己贡献的工具声明
 * 「按条件可用」（{@link #toolAvailability()}）与「按上下文动态描述」
 * （{@link #toolDescription()}）。绑定层（{@code agent_mcp_server} 授权）与本层的边界见
 * {@link ToolRegistry} 类注释。</p>
 */
public interface ToolCallbackContributor {

    /**
     * 返回承载一个或多个 {@code @Tool} 方法的实例（通常为 this）。
     */
    Object toolObject();

    /**
     * 本贡献者工具的「<b>按条件可用</b>」声明：工具名 → 判定函数。
     * 判定返回 {@code false} ⇒ 该工具从模型可见列表<b>摘除</b>
     * （不落库、不改前端、不改 MCP {@code tools/list} 暴露面）。
     *
     * <h2>契约（违反即违约）</h2>
     * <ol>
     *   <li><b>纯函数</b>：不得写库、发事件、做网络 / LLM 调用。</li>
     *   <li><b>必须廉价</b>：每 Turn 在 Runtime 热路径上调用一次（Spring bean 查询量级可接受，
     *       远程探测不可接受）。</li>
     *   <li><b>不得引用 {@code agent_mcp_server} 的授权事实</b>——本声明表达的是
     *       「平台/运行时此刻是否具备运行条件」，不是「某 Agent 是否被授权」；
     *       后者属绑定层。见 {@link ToolRegistry} 的三条禁令。</li>
     *   <li><b>依赖上下文的判定必须 fail-open</b>：{@code context} 为空或对应字段为 null 时
     *       返回 {@code true}（判为可用）——<b>不知道 ≠ 不具备</b>。</li>
     *   <li>不得抛异常。抛了按 {@code false} 处理（属违约），但实现侧会记 WARN 并 fail-open 保留，
     *       不阻断整批解析。</li>
     * </ol>
     *
     * <p>默认空 Map = 不声明 ⇒ 全部工具可用（零行为变化）。</p>
     */
    default Map<String, Predicate<ToolContext>> toolAvailability() {
        return Map.of();
    }

    /**
     * 本贡献者工具的「<b>按上下文动态描述</b>」声明：工具名 → 重写函数。
     *
     * <h2>契约</h2>
     * <ul>
     *   <li>返回 {@code null} / 空白 ⇒ 保持原声明（{@code @Tool(description=…)}）或目录静态描述，
     *       即「本轮无额外信息可补充」。</li>
     *   <li>同样受「纯函数 / 廉价 / 不得读授权事实 / 不得抛异常」四项约束
     *       （见 {@link #toolAvailability()}）。</li>
     *   <li><b>生效粒度 = Turn</b>：一次 {@code AgentRuntime.execute} 装配一次；
     *       同一 Turn 内 AgentLoop 的多轮迭代共享同一份 schema。真·每轮描述需将解析点
     *       下沉到循环内，REF-4 视需要评估。</li>
     * </ul>
     *
     * <p>默认空 Map = 不声明。</p>
     */
    default Map<String, Function<ToolContext, String>> toolDescription() {
        return Map.of();
    }
}
