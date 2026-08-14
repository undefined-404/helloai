package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.agent.entity.AgentMcpServer;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具开关/策略服务。
 * 读取 agent_mcp_server 表，提供工具启用判定、参数约束、频率限制查询。
 */
public interface AgentMcpServerService extends IService<AgentMcpServer> {

    /**
     * 为新建 Agent 启用 EXECUTOR 默认 10 工具（已存在跳过，安全幂等）。
     *
     * <p>由 {@link AgentService#register(String, com.helloai.common.constant.AgentRole, String)}
     * 在 {@code save(agent)} 之后调用，纳入同一事务。</p>
     *
     * @param agentId 新建 Agent ID
     * @return 实际新增的工具行数（已存在的不计）
     */
    int enableDefaultsForAgent(Long agentId);

    /**
     * 物理删除某 Agent 的全部 MCP 工具绑定（仅供 Agent 级联删除使用）。
     *
     * @return 实际删除行数
     */
    int physicalDeleteByAgentId(Long agentId);

    /**
     * 查询指定 Agent 的某个工具是否启用。
     */
    boolean isToolEnabled(Long agentId, String toolName);

    /**
     * 获取 Agent 所有启用的工具名列表。
     */
    List<String> getEnabledTools(Long agentId);

    /**
     * 获取工具的参数约束（如 pullTasks 的 max 上限）。
     */
    Map<String, Object> getParamConstraints(Long agentId, String toolName);

    /**
     * 获取工具的频率限制（次/分钟），0=不限。
     */
    int getRateLimit(Long agentId, String toolName);

    /**
     * 获取工具扩展配置。
     */
    Map<String, Object> getConfig(Long agentId, String toolName);
}
