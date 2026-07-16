-- =====================================================================
-- V21 AgentHub V1 P0-A：为所有 Agent 补齐值班工具 checkIn / checkOut
--
-- 背景：McpToolService.assertToolEnabled 依赖 agent_mcp_server 表事实源。
--       V13 只 seed 了 EXECUTOR 的执行侧工具集（pullTasks / ack / ...），
--       V21 引入 AgentHub V1 值班租约（agent_duty_lease）后，新增两个工具：
--         - checkIn  Agent 打卡上班（startLease）
--         - checkOut Agent 打卡下班（closeLease）
--       这两个工具与角色无关（EXECUTOR / PLANNER / REVIEWER / PATROL 都需要），
--       因此 CROSS JOIN 所有未删除 Agent。
--
-- 幂等：ON CONFLICT 依赖 agent_mcp_server (agent_id, tool_name) 部分唯一约束
--       （WHERE deleted = 0），重复执行安全。
-- =====================================================================
INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT a.id, tool.name, 1, 0, 'system', 'system'
FROM agent a
CROSS JOIN (VALUES
    ('checkIn'),
    ('checkOut')
) AS tool(name)
WHERE a.deleted = 0
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;
