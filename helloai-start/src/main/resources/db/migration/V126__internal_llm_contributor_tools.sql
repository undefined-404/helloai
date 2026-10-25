-- ============================================================
-- V126: 内部 LLM agent 补登 web_search / search_knowledge 授权
-- ------------------------------------------------------------
-- 背景（2026-10-25 实测事故）：
--   MiniMax agent（2110980198616358913）的 agent_mcp_server 表里没有
--   web_search / search_knowledge 两行——只有 13 个外部 MCP 轮询工具。
--   getEnabledToolsForAccess 的 available.contains("web_search") 恒为 false，
--   内部 LLM 执行工具集里 web_search 被静默剔除，模型唯一联网手段只剩
--   internal_run_in_sandbox 里 curl，导致 20 轮 MAX_ITERATIONS 失败。
--
-- 根因：
--   enableDefaultsForAgent 只注册 DEFAULT_EXECUTOR_TOOLS（13 个 MCP 会话工具），
--   不含 contributor 工具（web_search / search_knowledge）。内部 LLM agent
--   注册时未获得这两条授权行。
--
-- 本迁移：
--   给所有 access_type = 'API_KEY_LLM' 的 agent 补登 web_search / search_knowledge，
--   已存在跳过（ON CONFLICT 幂等）。
-- ============================================================

INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT a.id, t.tool_name, 1, 0, 'system_v126_backfill', 'system_v126_backfill'
FROM agent a
CROSS JOIN (VALUES ('web_search'), ('search_knowledge')) AS t(tool_name)
WHERE a.access_type = 'API_KEY_LLM'
  AND a.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM agent_mcp_server ams
      WHERE ams.agent_id = a.id AND ams.tool_name = t.tool_name AND ams.deleted = 0
  );

-- ============================================================
-- 验证
-- ============================================================

DO $$
DECLARE
    v_agent_count INT;
    v_backfill_count INT;
BEGIN
    SELECT COUNT(*) INTO v_agent_count
    FROM agent WHERE access_type = 'API_KEY_LLM' AND deleted = 0;

    SELECT COUNT(*) INTO v_backfill_count
    FROM agent_mcp_server ams
    JOIN agent a ON a.id = ams.agent_id
    WHERE a.access_type = 'API_KEY_LLM' AND a.deleted = 0
      AND ams.tool_name IN ('web_search', 'search_knowledge')
      AND ams.deleted = 0;

    RAISE NOTICE '[V126] API_KEY_LLM agent 数: %, 已授权 web_search/search_knowledge 行数: %',
        v_agent_count, v_backfill_count;
END $$;
