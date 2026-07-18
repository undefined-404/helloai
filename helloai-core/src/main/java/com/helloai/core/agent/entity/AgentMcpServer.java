package com.helloai.core.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * Agent 维度的 MCP 工具开关/策略/权限配置。
 * 同一 (agentId, toolName) 联合唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_mcp_server")
public class AgentMcpServer extends BaseEntity {

    /** Agent ID，关联 agent.id */
    private Long agentId;

    /** 工具名: pullTasks/ack/heartbeat/uploadArtifact/claimSubTask/reportBlocked */
    private String toolName;

    /** 是否启用: 0=禁用, 1=启用 */
    private Integer isEnabled;

    /** 频率限制（次/分钟），0=不限 */
    private Integer rateLimit;

    /** 参数约束 JSONB，如 {"max":50} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> paramConstraints;

    /** 扩展配置 JSONB，如 {"pullIntervalSec":60} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;
}
