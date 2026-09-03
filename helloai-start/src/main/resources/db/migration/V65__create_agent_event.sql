-- ============================================================
-- V65__create_agent_event.sql
-- 用途：Agent Event Stream 主表（Phase 0 B1）
-- 背景：
--   ADR-001 定稿 Run/Turn/Step 三层执行模型后，为其提供 append-only
--   事件轨迹存储（双轨非 ES：业务状态仍在 task/sub_task 表，
--   agent_event 只记事件轨迹，供对账 / 回放 / 观测）。
--   写入路径：AgentEventRecorder 同事务双写本表 + agent_outbox_event
--   （Outbox 由 AgentEventCompensationTask 投递 MQ），两表共享 event_id
--   作为 B3 对账键。B1.1 只建表，埋点在 B2。
-- 关键设计：
--   - event_id UNIQUE：双写幂等 / 对账键 / MQ 消息幂等键
--   - run_id + turn + step：(run_id, turn, step) 复合索引支撑按 Run 回放
--   - 通用审计列与 agent_outbox_event 同款（BaseEntity 映射）
-- 参考：doc/design/adr/ADR-001-run-turn-step-model.md；执行方案 B1.1
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_event (
    id              BIGINT      NOT NULL PRIMARY KEY,
    event_id        VARCHAR(64) NOT NULL UNIQUE,
    run_id          VARCHAR(64) NOT NULL,
    task_id         BIGINT,
    sub_task_id     BIGINT,
    turn            INT         NOT NULL DEFAULT 1,
    step            INT         NOT NULL DEFAULT 0,
    event_type      VARCHAR(64) NOT NULL,
    agent_id        BIGINT,
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    create_by       VARCHAR(64) NOT NULL DEFAULT '',
    update_by       VARCHAR(64) NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_agent_event_run ON agent_event(run_id, turn, step);
CREATE INDEX IF NOT EXISTS idx_agent_event_sub_task ON agent_event(sub_task_id, create_time);
DROP TRIGGER IF EXISTS update_agent_event_update_time ON agent_event;
CREATE TRIGGER update_agent_event_update_time BEFORE UPDATE ON agent_event
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE agent_event IS 'Agent 事件轨迹表（Phase 0 B1，append-only 双轨非 ES）';
COMMENT ON COLUMN agent_event.event_id IS '事件唯一 ID（与 agent_outbox_event.event_id 共享，B3 对账键）';
COMMENT ON COLUMN agent_event.run_id IS 'Run 标识（run-{taskId}-{roundNum}，见 ADR-001）';
COMMENT ON COLUMN agent_event.turn IS 'Turn 序号（一次 Agent 完整工作周期，从 1 起）';
COMMENT ON COLUMN agent_event.step IS 'Turn 内原子动作序号（从 0 起）；0 表示非 Step 级事件';
COMMENT ON COLUMN agent_event.event_type IS '事件类型（AgentEventType 枚举 snake_case）';