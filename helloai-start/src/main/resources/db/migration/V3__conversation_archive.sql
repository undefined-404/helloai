-- ============================================================
-- HelloAI V3: 对话归档表
-- ============================================================

CREATE TABLE IF NOT EXISTS conversation_archive (
    id              BIGINT NOT NULL PRIMARY KEY,
    sub_task_id     BIGINT       NOT NULL,
    content         TEXT         NOT NULL,
    message_count   INT          NOT NULL DEFAULT 0,
    total_tokens    INT,
    archive_time    TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_archive_sub_task ON conversation_archive(sub_task_id);
CREATE INDEX idx_archive_time ON conversation_archive(archive_time);
