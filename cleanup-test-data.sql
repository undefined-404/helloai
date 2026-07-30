-- ============================================================
-- HelloAI 测试数据清理（保留 sys_user + Flyway schema_history）
-- Usage: docker exec -i helloai-postgres psql -U postgres -d helloai < cleanup-test-data.sql
-- Ref:
--   .agents/skills/helloai-preflight/SKILL.md  (规则 5：保留 sys_user)
--   memory "数据库清理策略：仅保留 sys_user"
--
-- 清理范围（Flyway V1~V22 全部业务表，共 22 张）：
--   核心业务: task / module / agent / sub_task
--   鉴权/MCP: credential_vault / agent_mcp_server
--   收件箱/出箱: agent_inbox / agent_outbox_event / agent_command_outbox / event_consumption_log
--   对话/附件: conversation_archive / conversation_message / attachment
--   执行链路: review_record / reward_log / activity_log / request_log / rule
--   执行记录: agent_execution_record
--   提示词: prompt_template
--   值班租约: agent_duty_lease
--
-- 保留（永不动）：
--   sys_user            (含 admin/admin123 管理员)
--   flyway_schema_history (Flyway 元数据)
-- ============================================================

SET session_replication_role = replica;

TRUNCATE TABLE
    task, module, agent,
    sub_task, review_record, reward_log, activity_log,
    request_log, rule,
    agent_inbox, agent_outbox_event,
    conversation_archive, conversation_message, attachment,
    agent_execution_record, prompt_template,
    credential_vault, agent_mcp_server,
    agent_duty_lease,
    agent_command_outbox, event_consumption_log
RESTART IDENTITY CASCADE;

SET session_replication_role = origin;

-- ============================================================
-- 验证：保留表 count > 0，业务表 count = 0
-- ============================================================
SELECT 'agent (expect 0)'              AS tbl, COUNT(*) AS cnt FROM agent              WHERE deleted = 0
UNION ALL SELECT 'task (expect 0)',              COUNT(*) FROM task              WHERE deleted = 0
UNION ALL SELECT 'module (expect 0)',            COUNT(*) FROM module            WHERE deleted = 0
UNION ALL SELECT 'sub_task (expect 0)',          COUNT(*) FROM sub_task          WHERE deleted = 0
UNION ALL SELECT 'agent_duty_lease (expect 0)',  COUNT(*) FROM agent_duty_lease
UNION ALL SELECT 'agent_inbox (expect 0)',       COUNT(*) FROM agent_inbox
UNION ALL SELECT 'agent_outbox_event (expect 0)',COUNT(*) FROM agent_outbox_event
UNION ALL SELECT 'agent_command_outbox (expect 0)', COUNT(*) FROM agent_command_outbox
UNION ALL SELECT 'agent_mcp_server (expect 0)',  COUNT(*) FROM agent_mcp_server  WHERE deleted = 0
UNION ALL SELECT 'conversation_message (expect 0)', COUNT(*) FROM conversation_message
UNION ALL SELECT 'credential_vault (expect 0)',  COUNT(*) FROM credential_vault  WHERE deleted = 0
UNION ALL SELECT 'sys_user (expect >=1, keep)',  COUNT(*) FROM sys_user          WHERE deleted = 0
UNION ALL SELECT 'flyway_schema_history (expect >=22, keep)', COUNT(*) FROM flyway_schema_history
ORDER BY tbl;