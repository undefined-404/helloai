package com.helloai.core.agent.tool;

import java.util.List;

/**
 * 工具注册表（Phase 1 Step 2；长期思路 P0-1 AgentRuntime 固定成员之一）。
 *
 * <p>职责：持有平台已注册工具元数据目录，按 Agent 启用工具名解析命中的
 * {@link ToolDefinition}（启用/匹配契约）。与 {@code AgentSkillSpecService}
 * 同「resolve(声明) → matched(命中元数据)」Registry 形态——Skill / Tool 两侧
 * 共用同一元数据消费面（Step 2 收拢，不另建 SkillRegistry 平行类，§50.7）。</p>
 */
public interface ToolRegistry {

    /**
     * 按启用工具名解析命中的工具定义。
     *
     * <p>best-effort：null / 空 / 全未知均返回空列表，不抛异常；未知工具名跳过。</p>
     *
     * @param enabledToolNames Agent 当前启用的工具名列表（agent_mcp_server 派生）
     * @return 命中的工具定义列表（按入参顺序），永不为 null
     */
    List<ToolDefinition> resolve(List<String> enabledToolNames);
}
