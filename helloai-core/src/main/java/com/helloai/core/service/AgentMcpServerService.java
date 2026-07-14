package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.entity.AgentMcpServer;
import com.helloai.core.mapper.AgentMcpServerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 工具开关/策略服务。
 * 读取 agent_mcp_server 表，提供工具启用判定、参数约束、频率限制查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMcpServerService extends ServiceImpl<AgentMcpServerMapper, AgentMcpServer> {

    /**
     * EXECUTOR 默认启用的 7 个 MCP 工具清单（v2.5 M5 端到端业务循环必需）。
     * <p>
     * 注：EchoMcpTool.echo 是平台内置连通性诊断工具，不挂在 Agent 维度，
     * 走 spring-ai ToolCallbackProvider 自动注册，不在此列。
     * </p>
     * <ul>
     *   <li>{@code pullTasks}      —— 拉取 Agent 待处理收件箱（v2.4 §9.1）</li>
     *   <li>{@code ack}            —— 确认收件箱消息已处理（v2.4 §9.1）</li>
     *   <li>{@code claimSubTask}   —— 原子认领子任务（v2.4 §9.1）</li>
     *   <li>{@code heartbeat}      —— 心跳上报（v2.4 §9.1）</li>
     *   <li>{@code uploadArtifact} —— 上传产物附件元数据（v2.4 §9.1）</li>
     *   <li>{@code reportBlocked}  —— 上报任务阻塞（v2.5 补齐）</li>
     *   <li>{@code getAgentStatus} —— 查询 Agent 自身状态（v2.4 §9.1 helloai 此前缺失补齐）</li>
     * </ul>
     */
    public static final List<String> DEFAULT_EXECUTOR_TOOLS = List.of(
            "pullTasks",
            "ack",
            "claimSubTask",
            "heartbeat",
            "uploadArtifact",
            "submitResult",
            "reportBlocked",
            "getAgentStatus"
    );

    /** 默认启用 is_enabled 值。 */
    private static final int DEFAULT_IS_ENABLED = 1;
    /** 默认 rate_limit（次/分钟，0=不限）。 */
    private static final int DEFAULT_RATE_LIMIT = 0;
    /** create_by / update_by 系统默认标记。 */
    private static final String SYSTEM_OPERATOR = "system_agent_register";

    /**
     * 为新建 Agent 启用 EXECUTOR 默认 7 工具（已存在跳过，安全幂等）。
     * <p>
     * 由 {@link AgentService#register(String, com.helloai.common.constant.AgentRole, String)}
     * 在 {@code save(agent)} 之后调用，纳入同一事务。
     * </p>
     * <p>
     * 注意：AgentService.register() 对 role 不做限制（PLANNER / EXECUTOR / REVIEWER /
     * PATROL / 其他都可注册），但默认 7 工具按 EXECUTOR 业务循环最优集设计。
     * 若未来 PLANNER/REVIEWER 注册需要差异化工具集，
     * 应在 AgentService.register() 之前/之后按 role 分流。
     * 当前实现统一给非 EXECUTOR 也启用 7 工具 —— 因为 ON CONFLICT + 已存在跳过不会出错，
     * 且后续若需为 PLANNER 启用 planner_tools（如 decomposePlan），
     * 独立走 {@code enableSpecificTools(agentId, names)} 方法叠加即可。
     * </p>
     *
     * @param agentId 新建 Agent ID
     * @return 实际新增的工具行数（已存在的不计）
     */
    @Transactional(rollbackFor = Exception.class)
    public int enableDefaultsForAgent(Long agentId) {
        if (agentId == null) {
            log.warn("enableDefaultsForAgent: agentId 为空，跳过");
            return 0;
        }
        int inserted = 0;
        for (String toolName : DEFAULT_EXECUTOR_TOOLS) {
            AgentMcpServer row = new AgentMcpServer();
            row.setAgentId(agentId);
            row.setToolName(toolName);
            row.setIsEnabled(DEFAULT_IS_ENABLED);
            row.setRateLimit(DEFAULT_RATE_LIMIT);
            row.setCreateBy(SYSTEM_OPERATOR);
            row.setUpdateBy(SYSTEM_OPERATOR);
            try {
                save(row);
                inserted++;
                log.debug("enableDefaultsForAgent: 新增 tool={}, agentId={}", toolName, agentId);
            } catch (DuplicateKeyException e) {
                // (agent_id, tool_name) 部分唯一索引冲突 → 已存在，跳过
                log.debug("enableDefaultsForAgent: tool={} 已存在，跳过, agentId={}", toolName, agentId);
            }
        }
        log.info("enableDefaultsForAgent: agentId={}, 新增 {} 行（共 {} 工具）",
                agentId, inserted, DEFAULT_EXECUTOR_TOOLS.size());
        return inserted;
    }

    /**
     * 查询指定 Agent 的某个工具是否启用。
     */
    public boolean isToolEnabled(Long agentId, String toolName) {
        AgentMcpServer config = lambdaQuery()
                .eq(AgentMcpServer::getAgentId, agentId)
                .eq(AgentMcpServer::getToolName, toolName)
                .one();
        if (config == null && DEFAULT_EXECUTOR_TOOLS.contains(toolName)) {
            AgentMcpServer row = new AgentMcpServer();
            row.setAgentId(agentId);
            row.setToolName(toolName);
            row.setIsEnabled(DEFAULT_IS_ENABLED);
            row.setRateLimit(DEFAULT_RATE_LIMIT);
            row.setCreateBy(SYSTEM_OPERATOR);
            row.setUpdateBy(SYSTEM_OPERATOR);
            try {
                save(row);
            } catch (DuplicateKeyException e) {
            }
            config = lambdaQuery()
                    .eq(AgentMcpServer::getAgentId, agentId)
                    .eq(AgentMcpServer::getToolName, toolName)
                    .one();
        }
        return config != null && config.getIsEnabled() != null && config.getIsEnabled() == 1;
    }

    /**
     * 获取 Agent 所有启用的工具名列表。
     */
    public List<String> getEnabledTools(Long agentId) {
        return lambdaQuery()
                .eq(AgentMcpServer::getAgentId, agentId)
                .eq(AgentMcpServer::getIsEnabled, 1)
                .list()
                .stream()
                .map(AgentMcpServer::getToolName)
                .collect(Collectors.toList());
    }

    /**
     * 获取工具的参数约束（如 pullTasks 的 max 上限）。
     */
    public Map<String, Object> getParamConstraints(Long agentId, String toolName) {
        AgentMcpServer config = lambdaQuery()
                .eq(AgentMcpServer::getAgentId, agentId)
                .eq(AgentMcpServer::getToolName, toolName)
                .one();
        return config != null ? config.getParamConstraints() : null;
    }

    /**
     * 获取工具的频率限制（次/分钟），0=不限。
     */
    public int getRateLimit(Long agentId, String toolName) {
        AgentMcpServer config = lambdaQuery()
                .eq(AgentMcpServer::getAgentId, agentId)
                .eq(AgentMcpServer::getToolName, toolName)
                .one();
        return config != null && config.getRateLimit() != null ? config.getRateLimit() : 0;
    }

    /**
     * 获取工具扩展配置。
     */
    public Map<String, Object> getConfig(Long agentId, String toolName) {
        AgentMcpServer config = lambdaQuery()
                .eq(AgentMcpServer::getAgentId, agentId)
                .eq(AgentMcpServer::getToolName, toolName)
                .one();
        return config != null ? config.getConfig() : null;
    }
}
