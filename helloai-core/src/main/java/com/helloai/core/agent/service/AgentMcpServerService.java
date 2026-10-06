package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.common.constant.AgentAccessType;
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
     * 按接入类型获取 Agent 可注入的启用工具名列表（L3 P1-1 内循环工具注入修复）。
     *
     * <p><b>语义</b>：在 {@link #getEnabledTools(Long)} 的授权结果之上，按
     * {@code accessType} 追加一层「可注入性」过滤 ——</p>
     * <ul>
     *   <li>{@code API_KEY_LLM}（内部 LLM 执行者，进程内 {@code AgentLoop} 无 MCP 会话）：
     *       剔除 MCP 会话类工具（pullTasks / ack / claimSubTask / startSubTask /
     *       getSubTaskDetail / heartbeat / uploadArtifact / submitResult / reportBlocked /
     *       getAgentStatus / getDepsSummary / checkIn / checkOut）——它们依赖
     *       {@code McpAuthContext} 的 sessionId，进程内调用必然 401；</li>
     *   <li>{@code CLI_CLIENT} / {@code WEB_BROWSER} / {@code null}：<b>原样返回</b>，
     *       与 {@link #getEnabledTools(Long)} 逐字一致（外部 Agent 唯一在用链路，零回归）。</li>
     * </ul>
     *
     * <p><b>与 {@code getEnabledTools} 的关系</b>：后者语义<b>不变</b>（其它调用点/授权查询
     * 仍取全集）；本方法仅用于「工具注入执行上下文」这一消费场景。</p>
     *
     * @param agentId    Agent ID
     * @param accessType 接入类型（可 null，视为非 API_KEY_LLM ⇒ 不过滤）
     * @return 可注入的工具名列表（绝不返回 {@code null}）
     */
    List<String> getEnabledToolsForAccess(Long agentId, AgentAccessType accessType);

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
