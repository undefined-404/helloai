-- V36__task_running_spec_tables.sql
-- Phase B 前置：TaskRunningSpec + ExecutionRecord 独立表
-- 当前 Phase A 仍以 task.context.runningSpec JSONB 存储，建表仅为 Phase B 切换做准备

CREATE TABLE IF NOT EXISTS task_running_spec (
    id              BIGINT       NOT NULL PRIMARY KEY,
    task_id         BIGINT       NOT NULL UNIQUE REFERENCES task(id),
    version         INT          NOT NULL DEFAULT 1,
    baseline        JSONB,
    context_summary TEXT,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE task_running_spec IS 'Task Running Spec 主表（Phase B 目标态）：Planner 确认拆解时写入 Baseline，Executor 每次回填后自动编译 ContextSummary';

DROP TRIGGER IF EXISTS update_task_running_spec_update_time ON task_running_spec;
CREATE TRIGGER update_task_running_spec_update_time
    BEFORE UPDATE ON task_running_spec
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

-- ────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS task_execution_record (
    id               BIGINT       NOT NULL PRIMARY KEY,
    task_id          BIGINT       NOT NULL REFERENCES task(id),
    sub_task_id      BIGINT       NOT NULL,
    agent_id         BIGINT,
    title            VARCHAR(500),
    summary          TEXT,
    key_decisions    JSONB        DEFAULT '[]'::jsonb,
    downstream_notes JSONB        DEFAULT '[]'::jsonb,
    deliverables     JSONB        DEFAULT '[]'::jsonb,
    create_by        VARCHAR(64)  NOT NULL DEFAULT '',
    update_by        VARCHAR(64)  NOT NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- (task_id, sub_task_id) 联合唯一：rework 时 upsert 覆盖旧记录，保证同一子任务始终只有 1 条最新记录
CREATE UNIQUE INDEX IF NOT EXISTS idx_execution_record_task_subtask
    ON task_execution_record(task_id, sub_task_id);

COMMENT ON TABLE task_execution_record IS 'Executor 执行回填记录（Phase B 目标态）：每次执行结束写入一条，按 (task_id, sub_task_id) 唯一约束 rework 自动覆盖';

DROP TRIGGER IF EXISTS update_task_execution_record_update_time ON task_execution_record;
CREATE TRIGGER update_task_execution_record_update_time
    BEFORE UPDATE ON task_execution_record
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();
