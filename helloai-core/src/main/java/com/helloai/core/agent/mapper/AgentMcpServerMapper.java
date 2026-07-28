package com.helloai.core.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.helloai.core.agent.entity.AgentMcpServer;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentMcpServerMapper extends BaseMapper<AgentMcpServer> {

    /** 物理删除某 Agent 的全部 MCP 工具绑定（绕过 @TableLogic，仅供 Agent 级联删除使用）。 */
    @Delete("DELETE FROM agent_mcp_server WHERE agent_id = #{agentId}")
    int physicalDeleteByAgentId(@Param("agentId") Long agentId);
}
