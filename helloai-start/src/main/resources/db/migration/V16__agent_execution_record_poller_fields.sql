-- ============================================================
-- V16__agent_execution_record_poller_fields.sql
-- 用途：DB Poller 消费模型字段补全 — Phase 2A §5.1 阶段一最后一项
-- 背景：
--   现状：LocalExecutionCommandConsumer 通过 Spring 事务事件 (AFTER_COMMIT) 触发，
--         调度侧只创建 agent_execution_record(status=PENDING) 行后通过
--         ApplicationEventPublisher 发事件，@Async 消费者在线程池里接管执行。
--         若应用重启或线程池积压，PENDING 行会"孤儿化"——既无事件驱动，也无任何
--         消费者再次触发，只能等 ExecutionCompensationTask 按 PENDING 超时补偿。
--   目标：DB Poller 独立消费模型。
--         - 主路径仍保留事务事件（实时性）
--         - 兜底路径新增 DB Poller：扫"长时间未被消费的 PENDING"行，重新触发消费
--         - Poller 不依赖事务事件、不依赖 @Async 线程池
--   字段语义：
--     trigger      调度来源：assigned / reassigned / retry / poll-recovery
--     agent_id     目标 Agent，与 ExecutionCommand.agentId 对齐，便于 Poller 直接从行内恢复命令
--     access_type  目标 Agent 接入类型（API_KEY_LLM / CLI / BROWSER / MCP），便于 Poller
--                  在不查 Agent 表的情况下恢复 ExecutionCommand
--     last_attempt_at
--                  最近一次 Poller 扫描该行的时间；NULL 表示尚未被 Poller 触及过。
--                  Poller 扫描条件：status='PENDING' AND (last_attempt_at IS NULL OR last_attempt_at < now - threshold)
-- 参考：架构设计参考 §5.1 第一阶段 / 调度解耦重构分析 §7 阶段 1
-- ============================================================

ALTER TABLE agent_execution_record
    ADD COLUMN IF NOT EXISTS trigger VARCHAR(32),
    ADD COLUMN IF NOT EXISTS agent_id BIGINT,
    ADD COLUMN IF NOT EXISTS access_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMPTZ;

COMMENT ON COLUMN agent_execution_record.trigger IS '命令触发来源：assigned / reassigned / retry / poll-recovery';
COMMENT ON COLUMN agent_execution_record.agent_id IS '目标 Agent ID（冗余存储，便于 Poller 恢复 ExecutionCommand）';
COMMENT ON COLUMN agent_execution_record.access_type IS '目标 Agent 接入类型（冗余存储，便于 Poller 恢复 ExecutionCommand）';
COMMENT ON COLUMN agent_execution_record.last_attempt_at IS 'DB Poller 最近一次扫描该行的时间；NULL 表示尚未被 Poller 触及过';

-- 兜底扫描专用索引：只对 PENDING 行有意义，且按 last_attempt_at 升序遍历
-- 使用部分索引避免对其他状态的行建立冗余索引
CREATE INDEX IF NOT EXISTS idx_exec_record_pending_attempt
    ON agent_execution_record(last_attempt_at, create_time)
    WHERE status = 'PENDING';

-- 验证日志（启动时输出受影响行数）
DO $$
DECLARE
    added_columns INTEGER;
BEGIN
    SELECT COUNT(*) INTO added_columns
    FROM information_schema.columns
    WHERE table_name = 'agent_execution_record'
      AND column_name IN ('trigger', 'agent_id', 'access_type', 'last_attempt_at');
    RAISE NOTICE '[V16] agent_execution_record poller 字段补全完成，已存在相关列数 = %', added_columns;
END $$;