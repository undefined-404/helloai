-- ============================================================
-- HelloAI V6: Agent 执行记录表（防 ACK 丢失）
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_execution_record (
    id              BIGINT NOT NULL PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL,
    sub_task_id     BIGINT       NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    worker_node     VARCHAR(64),
    start_time      TIMESTAMPTZ,
    end_time        TIMESTAMPTZ,
    error_msg       TEXT,
    retry_count     INT          NOT NULL DEFAULT 0,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_exec_record_status_time ON agent_execution_record(status, create_time);
CREATE INDEX idx_exec_record_sub_task ON agent_execution_record(sub_task_id, status);
CREATE INDEX idx_exec_record_event ON agent_execution_record(event_id);
CREATE TRIGGER update_exec_record_update_time BEFORE UPDATE ON agent_execution_record
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();
