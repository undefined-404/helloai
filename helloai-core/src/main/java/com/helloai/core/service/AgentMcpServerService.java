package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.entity.AgentMcpServer;
import com.helloai.core.mapper.AgentMcpServerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
     * 查询指定 Agent 的某个工具是否启用。
     */
    public boolean isToolEnabled(Long agentId, String toolName) {
        AgentMcpServer config = lambdaQuery()
                .eq(AgentMcpServer::getAgentId, agentId)
                .eq(AgentMcpServer::getToolName, toolName)
                .one();
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
