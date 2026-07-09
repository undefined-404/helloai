-- ============================================================
-- V2__init_agent_mcp_server_defaults.sql
-- 用途：M5 端到端业务循环补全 — 历史 EXECUTOR Agent 的 MCP 工具默认启用登记
-- 背景：
--   V1 只对历史 EXECUTOR 插入了 6 工具（pullTasks/ack/heartbeat/claimSubTask/
--   uploadArtifact/reportBlocked）。v2.4 §9.1 要求 helloai 端补齐的
--   `getAgentStatus` 当时未在 6 工具清单内，导致历史 EXECUTOR 调
--   getAgentStatus 会被 `assertToolEnabled` 抛 BizException。
-- 策略：
--   1) 对历史 EXECUTOR 没启用 `getAgentStatus` 的，INSERT ON CONFLICT DO NOTHING
--   2) 不动已有的 6 行（保留其 rate_limit / param_constraints / config）
--   3) 后续新建 Agent 由 AgentService.register() 走
--      AgentMcpServerService.enableDefaultsForAgent(agentId) 自动补齐 7 行
-- 参考：v2.5 路线图 §3.13 / 附录 E.3 M5 / 附录 F.5 实施日志
-- ============================================================

-- 1) 兜底：补 getAgentStatus 给历史 EXECUTOR
INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by, remark)
SELECT a.id,
       'getAgentStatus',
       1,
       0,
       'system_v2_migration',
       'system_v2_migration',
       'M5 migration: getAgentStatus 默认启用（v2.4 §9.1 helloai 此前缺失补齐）'
FROM agent a
WHERE a.role = 'EXECUTOR'
  AND a.deleted = 0
  AND NOT EXISTS (
        SELECT 1
        FROM agent_mcp_server ams
        WHERE ams.agent_id = a.id
          AND ams.tool_name = 'getAgentStatus'
          AND ams.deleted = 0
    )
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;

-- 2) 验证日志（启动时输出受影响行数）
DO $$
DECLARE
    affected INTEGER;
BEGIN
    GET DIAGNOSTICS affected = ROW_COUNT;
    RAISE NOTICE '[V2] getAgentStatus 补登记完成，影响行数 = %', affected;
END $$;
