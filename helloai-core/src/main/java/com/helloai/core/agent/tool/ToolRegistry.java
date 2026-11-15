package com.helloai.core.agent.tool;

import java.util.List;

/**
 * 工具注册表（长期思路 P0-1 AgentRuntime 固定成员之一）。
 *
 * <p>职责：持有平台已注册工具元数据目录，按 Agent 启用工具名解析「<b>生效形态</b>」的工具定义
 * （启用/匹配契约 + REF-1.3 的条件可用 / 动态描述两个语义位）。与 {@code AgentSkillSpecService}
 * 同「resolve(声明) → matched(命中元数据)」Registry 形态，Skill / Tool 共用同一元数据消费面
 * （收拢，不另建 SkillRegistry 平行类，§50.7）。</p>
 *
 * <h2>绑定层 vs 注册层：两个不同的事实，分开表达（REF-1.3，D-2026-10-09-4④）</h2>
 *
 * <p><b>绑定层</b>（{@code agent_mcp_server} / {@code AgentMcpServerService}）＝「Agent 被授权使用哪些工具」：
 * 持久化 DB 行、管理页可改、按 Agent 粒度。{@code is_enabled} 语义是<b>授权/未授权</b>而非<b>能力具备/不具备</b>；
 * 不可关闭清单 {@code CRITICAL_TOOLS} 是本层下界。</p>
 *
 * <p><b>注册层</b>（本接口 + {@link ToolCallbackContributor} 的 {@code toolAvailability}/{@code toolDescription}）
 * ＝「平台/运行时此刻是否具备该工具运行条件」：进程内事实（凭据/知识库/本轮上下文）、不落库、不可管理页编辑、
 * 随进程状态与 Turn 上下文变化。「条件可用」与「动态描述」两个语义位属本层。</p>
 *
 * <p><b>合成 = 交集</b>：模型可见工具 = 绑定层授权集 ∩ 注册层可用集；两层互不改写对方
 * （注册层摘除<b>不写</b> {@code agent_mcp_server}，绑定层禁用<b>不改</b>声明）。<b>三条禁令</b>（违反即层次错位）：</p>
 * <ol>
 *   <li>不得用 {@code agent_mcp_server} 表达「不具备」——写脏数据，且进程状态恢复后无法自动复原；</li>
 *   <li>不得用注册层声明表达「某 Agent 未授权」——会让声明者承担授权职责并被迫读 DB，把已被端口反转消除的反向依赖拉回；</li>
 *   <li>条件不可用的摘除<b>必须</b>表现为「不在模型可见列表」，不得退化为「description 里写『不要调用』」（模型不保证遵守）。</li>
 * </ol>
 *
 * <p><b>作用面边界</b>：只作用于<b>进程内 Runtime 的模型可见列表</b>
 * （{@code RuntimeTurnExecutor} → {@code AgentLoopInput.enabledToolCallbacks}），<b>不作用</b>于 MCP {@code tools/list}
 * 暴露面（{@code McpToolConfig.mcpToolCallbacks()}）——外部 MCP Agent 仍从平台目录看到全部注册工具
 * （外部链路已有优雅降级兜底）。</p>
 *
 * <p><b>生效粒度 = Turn</b>：一次 {@code AgentRuntime.execute} 装配一次，同一 Turn 内 AgentLoop 多轮共享同一份 schema
 * （{@code ChatModelToolLoop} 每轮用同一 {@code AgentLoopInput} 实例）。真·每轮描述需将解析点下沉到循环内（REF-4 视需要评估）。</p>
 */
public interface ToolRegistry {

    /**
     * 按启用工具名解析「<b>生效形态</b>」的工具定义。
     *
     * <p><b>生效形态</b> = 条件可用过滤后的子集 + 已重写（或目录静态）的描述。
     * 本方法的结果是<b>模型可见工具的唯一判据</b>——消费方
     * （{@code RuntimeTurnExecutor}）以其装配 {@code AgentLoop} 可见 schema，
     * 不再自行按名字集合过滤。</p>
     *
     * <p><b>三态契约</b>（判定顺序固定）：</p>
     * <ol>
     *   <li><b>条件不可用 ⇒ 摘除</b>：名字被某个 {@link ToolCallbackContributor} 声明为不可用
     *       ⇒ 不出现在结果中。<b>该判定不依赖工具目录</b>，目录降级时照常生效。</li>
     *   <li><b>描述</b>：声明者的动态描述优先；无声明 / 声明返回空白 ⇒ 取目录静态描述；
     *       目录不可用 ⇒ 空白串，语义 =「无生效描述，调用方保持 ToolCallback 原描述」。</li>
     *   <li><b>未知工具 ⇒ 跳过</b>：目录已加载且名字不在其中 ⇒ 跳过（与 REF-1.3 前口径一致）；
     *       <b>目录降级</b>（加载失败——未知性来自平台故障而非入参）⇒ <b>fail-open 保留</b>，
     *       「未知」不构成摘除理由。</li>
     * </ol>
     *
     * <p>best-effort：null / 空入参返回空列表，不抛异常；恒非 null；保序（按入参顺序）、去重、去空。</p>
     *
     * @param enabledToolNames Agent 当前启用的工具名（{@code agent_mcp_server} 派生 ∪ 技能 requiredTools）
     * @param context          解析上下文（可空 ⇒ 按 {@link ToolContext#empty()} 的 fail-open 约定处理）
     * @return 生效工具定义列表（按入参顺序），永不为 null
     */
    List<ToolDefinition> resolve(List<String> enabledToolNames, ToolContext context);
}
