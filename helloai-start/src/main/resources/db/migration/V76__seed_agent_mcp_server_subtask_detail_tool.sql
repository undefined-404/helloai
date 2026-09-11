-- ============================================================
-- V76__seed_agent_mcp_server_subtask_detail_tool.sql
-- 用途：为 Agent 启用 MCP 工具 getSubTaskDetail（子任务详情下发）
-- 背景：
--   外部执行者经 MCP 此前只能看到收件箱摘要（仅「交付物」一项），而审查侧按
--   sub_task.acceptance 逐条核验（subtask-review 轨道 A）——执行者看不到验收标准
--   却要被按验收标准判定，属独立的结构性错配。新增 getSubTaskDetail 工具把子任务
--   全文（content 执行边界 / deliverable 交付物 / acceptance 验收标准 /
--   constraints 执行约束 / uncertainties 不确定性申报）一次性下发；
--   claimSubTask 成功返回体同时内联 detail，免一次往返。
-- 机制：
--   assertToolEnabled 以 agent_mcp_server 为事实源（fail-close），未 seed 的工具
--   调用会被拒绝，故新增工具必须随迁移补齐行。
--   本工具角色无关（EXECUTOR 开工自检 / PLANNER 排障 / REVIEWER 复核均可能读取），
--   故 CROSS JOIN 所有未删除 Agent（同 V21 checkIn/checkOut 模式）；实际可见性由
--   服务端授权判定（仅「已分配给本 Agent」或「未分配且 PENDING（可认领）」可查）。
-- 幂等：ON CONFLICT 依赖 agent_mcp_server (agent_id, tool_name) 部分唯一约束
--       （WHERE deleted = 0），重复执行安全。
-- 参考：McpToolService.getSubTaskDetail / McpMcpServer @Tool(name = "getSubTaskDetail")
-- ============================================================

INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT a.id, 'getSubTaskDetail', 1, 0, 'system', 'system'
FROM agent a
WHERE a.deleted = 0
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V76] agent_mcp_server 已启用 getSubTaskDetail（工具面 12 项）';
END $$;
