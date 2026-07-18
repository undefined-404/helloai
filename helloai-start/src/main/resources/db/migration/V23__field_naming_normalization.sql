-- ============================================================
-- V23__field_naming_normalization.sql
-- 用途：字段命名规范化 —— 统一 xxx_time 时间后缀 / xxx_id 外键后缀 / 避开关键字
-- 涉及 20 列：16 个 _at 时间字段 + 3 个 agent 引用 + 1 个关键字 trigger
-- 说明：
--   1. PostgreSQL RENAME COLUMN 自动更新引用该列的索引/约束定义，无需重建：
--      idx_agent_command_outbox_pending_scan (next_retry_at)
--      idx_agent_command_outbox_sent_scan    (last_sent_at)
--      idx_exec_record_pending_attempt       (last_attempt_at, create_time)
--      idx_agent_external_failure_scan       (consecutive_failure_count, last_fallback_at)
--   2. 列注释随列保留，无需重建。
--   3. 本脚本必须与代码同步发布：entity 字段改名、
--      AgentMapper.xml / AgentDutyLeaseMapper.xml / SubTaskMapper.xml 裸列名、
--      AgentHealthCheckTask 中的 UpdateWrapper 字符串列名。
-- ============================================================

-- ---------- 1. agent：5 个时间字段 ----------
ALTER TABLE agent RENAME COLUMN last_seen_at     TO last_seen_time;
ALTER TABLE agent RENAME COLUMN last_active_at   TO last_active_time;
ALTER TABLE agent RENAME COLUMN offline_at       TO offline_time;
ALTER TABLE agent RENAME COLUMN last_failure_at  TO last_failure_time;
ALTER TABLE agent RENAME COLUMN last_fallback_at TO last_fallback_time;

-- ---------- 2. agent_execution_record：1 时间 + 1 关键字 ----------
ALTER TABLE agent_execution_record RENAME COLUMN last_attempt_at TO last_attempt_time;
ALTER TABLE agent_execution_record RENAME COLUMN trigger         TO trigger_type;

-- ---------- 3. agent_command_outbox：3 个时间字段 ----------
ALTER TABLE agent_command_outbox RENAME COLUMN next_retry_at TO next_retry_time;
ALTER TABLE agent_command_outbox RENAME COLUMN last_sent_at  TO last_sent_time;
ALTER TABLE agent_command_outbox RENAME COLUMN confirmed_at  TO confirmed_time;

-- ---------- 4. agent_duty_lease：3 个时间字段 ----------
ALTER TABLE agent_duty_lease RENAME COLUMN started_at      TO start_time;
ALTER TABLE agent_duty_lease RENAME COLUMN last_renewed_at TO last_renew_time;
ALTER TABLE agent_duty_lease RENAME COLUMN expires_at      TO expire_time;

-- ---------- 5. agent_inbox：2 个时间字段 ----------
ALTER TABLE agent_inbox RENAME COLUMN read_at    TO read_time;
ALTER TABLE agent_inbox RENAME COLUMN expires_at TO expire_time;

-- ---------- 6. credential_vault：1 个时间字段 ----------
ALTER TABLE credential_vault RENAME COLUMN expires_at TO expire_time;

-- ---------- 7. sub_task：1 时间 + 1 agent 引用 ----------
ALTER TABLE sub_task RENAME COLUMN completed_at   TO complete_time;
ALTER TABLE sub_task RENAME COLUMN assigned_agent TO assigned_agent_id;

-- ---------- 8. review_record / patrol_record：agent 引用 ----------
ALTER TABLE review_record RENAME COLUMN reviewer_agent TO reviewer_agent_id;
ALTER TABLE patrol_record RENAME COLUMN patrol_agent   TO patrol_agent_id;

-- ---------- 注释同步（原注释随列保留，这里补充改名溯源） ----------
COMMENT ON COLUMN agent_execution_record.trigger_type IS '命令触发来源：assigned / reassigned / retry / poll-recovery（原 trigger 列，避开关键字）';
COMMENT ON COLUMN sub_task.assigned_agent_id          IS '被分配的 Agent ID（原 assigned_agent，统一 xxx_id 后缀）';
COMMENT ON COLUMN review_record.reviewer_agent_id     IS '评审 Agent ID（原 reviewer_agent）';
COMMENT ON COLUMN patrol_record.patrol_agent_id       IS '巡检 Agent ID（原 patrol_agent）';

-- ---------- 验证：新列名应命中 20 ----------
DO $$
DECLARE
    renamed INTEGER;
BEGIN
    SELECT COUNT(*) INTO renamed
    FROM information_schema.columns
    WHERE (table_name = 'agent'                  AND column_name IN ('last_seen_time','last_active_time','offline_time','last_failure_time','last_fallback_time'))
       OR (table_name = 'agent_execution_record' AND column_name IN ('last_attempt_time','trigger_type'))
       OR (table_name = 'agent_command_outbox'   AND column_name IN ('next_retry_time','last_sent_time','confirmed_time'))
       OR (table_name = 'agent_duty_lease'       AND column_name IN ('start_time','last_renew_time','expire_time'))
       OR (table_name = 'agent_inbox'            AND column_name IN ('read_time','expire_time'))
       OR (table_name = 'credential_vault'       AND column_name =  'expire_time')
       OR (table_name = 'sub_task'               AND column_name IN ('complete_time','assigned_agent_id'))
       OR (table_name = 'review_record'          AND column_name =  'reviewer_agent_id')
       OR (table_name = 'patrol_record'          AND column_name =  'patrol_agent_id');
    RAISE NOTICE '[V23] 字段命名规范化完成，新列名命中数 = %（预期 20）', renamed;
END $$;
