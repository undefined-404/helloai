-- ============================================================
-- HelloAI 业务数据清理（保留基座配置，本地/生产通用，2026-09-30）
-- Usage（本地 docker）:
--   docker exec -i helloai-postgres psql -U postgres -d helloai < cleanup-test-data.sql
-- Usage（生产/其他环境）: 按实际连接方式执行同一文件
--
-- 背景：G-002 双轨→单轨硬切后从零验证。用户决策：除基座配置外，
-- 全部业务数据（测试数据）清理，本地与生产同一口径执行。
--
-- 保留（不清理，视作平台基础信息）：
--   LLM 模型目录: llm_provider / llm_provider_model
--   LLM 凭据:     credential_vault（API Key，与模型/Agent 强绑定，清后需重配）
--   已注册 Agent: agent / agent_mcp_server（工具注册）/ agent_quality_profile（质量画像）
--   系统基座:     sys_user / sys_config / sys_role / sys_permission /
--                 sys_user_role / sys_role_permission / sys_depart / sys_user_depart /
--                 sys_position / sys_user_position / sys_permission_data_rule /
--                 prompt_template（角色模板 seed）/ rule（全局默认规则 seed）/
--                 flyway_schema_history（Flyway 元数据，永不动）
--
-- 清理（35 张业务表，DO 动态 TRUNCATE：缺表自动跳过，本地/生产表差异兼容）：
--   任务链:   task / module / sub_task / task_timeline / task_iteration /
--             task_running_spec / task_execution_record / review_record /
--             review_recheck_log / reward_log / activity_log / patrol_record / request_log
--   执行痕迹: agent_execution_record / agent_session / agent_event / agent_inbox /
--             agent_duty_lease / agent_outbox_event / agent_command_outbox /
--             event_consumption_log
--   需求/对话: requirement_conversation / requirement_message /
--             conversation_archive / conversation_message
--   审计/附件: credential_audit_log / attachment
--   基础设施业务: mq_dead_letter_archive / long_term_memory / browser_session /
--             workflow_template / workflow_template_version / workflow_instance /
--             team / team_member
--
-- 原子性：BEGIN/COMMIT 包裹；任一表失败整体回滚，不会半清状态。
-- ============================================================

BEGIN;

SET session_replication_role = replica;

DO $$
DECLARE
    t TEXT;
    tables TEXT[] := ARRAY[
        -- 任务链
        'task', 'module', 'sub_task', 'task_timeline', 'task_iteration',
        'task_running_spec', 'task_execution_record', 'review_record', 'review_recheck_log',
        'reward_log', 'activity_log', 'patrol_record', 'request_log',
        -- 执行痕迹
        'agent_execution_record', 'agent_session', 'agent_event',
        'agent_inbox', 'agent_duty_lease', 'agent_outbox_event', 'agent_command_outbox',
        'event_consumption_log',
        -- 需求/对话
        'requirement_conversation', 'requirement_message',
        'conversation_archive', 'conversation_message',
        -- 审计/附件
        'credential_audit_log', 'attachment',
        -- 基础设施业务
        'mq_dead_letter_archive', 'long_term_memory', 'browser_session',
        'workflow_template', 'workflow_template_version', 'workflow_instance',
        'team', 'team_member'
    ];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = current_schema() AND table_name = t
        ) THEN
            EXECUTE format('TRUNCATE TABLE %I RESTART IDENTITY CASCADE', t);
        END IF;
    END LOOP;
END $$;

SET session_replication_role = origin;

COMMIT;

-- ============================================================
-- 验证：保留表显示实际 count（keep），业务表 expect 0
-- ============================================================
SELECT 'agent (keep)'                    AS tbl, COUNT(*) AS cnt FROM agent
UNION ALL SELECT 'agent_mcp_server (keep)',       COUNT(*) FROM agent_mcp_server
UNION ALL SELECT 'agent_quality_profile (keep)',  COUNT(*) FROM agent_quality_profile
UNION ALL SELECT 'credential_vault (keep)',       COUNT(*) FROM credential_vault
UNION ALL SELECT 'llm_provider (keep)',           COUNT(*) FROM llm_provider
UNION ALL SELECT 'llm_provider_model (keep)',     COUNT(*) FROM llm_provider_model
UNION ALL SELECT 'sys_user (keep)',               COUNT(*) FROM sys_user
UNION ALL SELECT 'sys_role (keep)',               COUNT(*) FROM sys_role
UNION ALL SELECT 'prompt_template (keep)',        COUNT(*) FROM prompt_template
UNION ALL SELECT 'rule (keep)',                   COUNT(*) FROM rule
UNION ALL SELECT 'sys_config (keep)',             COUNT(*) FROM sys_config
UNION ALL SELECT 'flyway_schema_history (keep)',  COUNT(*) FROM flyway_schema_history
UNION ALL SELECT 'task (expect 0)',               COUNT(*) FROM task
UNION ALL SELECT 'sub_task (expect 0)',           COUNT(*) FROM sub_task
UNION ALL SELECT 'task_timeline (expect 0)',      COUNT(*) FROM task_timeline
UNION ALL SELECT 'agent_execution_record (expect 0)', COUNT(*) FROM agent_execution_record
UNION ALL SELECT 'agent_session (expect 0)',      COUNT(*) FROM agent_session
UNION ALL SELECT 'agent_duty_lease (expect 0)',   COUNT(*) FROM agent_duty_lease
UNION ALL SELECT 'agent_outbox_event (expect 0)', COUNT(*) FROM agent_outbox_event
UNION ALL SELECT 'event_consumption_log (expect 0)', COUNT(*) FROM event_consumption_log
UNION ALL SELECT 'requirement_conversation (expect 0)', COUNT(*) FROM requirement_conversation
UNION ALL SELECT 'conversation_message (expect 0)', COUNT(*) FROM conversation_message
UNION ALL SELECT 'attachment (expect 0)',         COUNT(*) FROM attachment
UNION ALL SELECT 'credential_audit_log (expect 0)', COUNT(*) FROM credential_audit_log
UNION ALL SELECT 'mq_dead_letter_archive (expect 0)', COUNT(*) FROM mq_dead_letter_archive
UNION ALL SELECT 'workflow_instance (expect 0)',  COUNT(*) FROM workflow_instance
UNION ALL SELECT 'team (expect 0)',               COUNT(*) FROM team
ORDER BY tbl;