-- ============================================================
-- HelloAI V2: Outbox 事件表
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_outbox_event (
    id              BIGINT NOT NULL PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL UNIQUE,
    event_type      VARCHAR(64)  NOT NULL,
    routing_key     VARCHAR(128) NOT NULL,
    payload         JSONB        NOT NULL,
    status          SMALLINT     NOT NULL DEFAULT 0,
    retry_count     INT          NOT NULL DEFAULT 0,
    error_msg       TEXT,
    next_retry_time TIMESTAMPTZ,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX idx_outbox_status_time ON agent_outbox_event(status, create_time);
CREATE INDEX idx_outbox_retry ON agent_outbox_event(status, next_retry_time);
CREATE TRIGGER update_outbox_update_time BEFORE UPDATE ON agent_outbox_event
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();
