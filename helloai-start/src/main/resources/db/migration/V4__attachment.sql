-- ============================================================
-- HelloAI V4: 附件表（MinIO 对象存储）
-- ============================================================

CREATE TABLE IF NOT EXISTS attachment (
    id              BIGINT NOT NULL PRIMARY KEY,
    sub_task_id     BIGINT,
    file_name       VARCHAR(255) NOT NULL,
    file_type       VARCHAR(64)  NOT NULL,
    mime_type       VARCHAR(128) NOT NULL,
    file_size       BIGINT       NOT NULL,
    bucket_name     VARCHAR(64)  NOT NULL DEFAULT 'helloai',
    object_key      VARCHAR(255) NOT NULL,
    storage_url     VARCHAR(500),
    preview_url     VARCHAR(500),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_attachment_sub_task ON attachment(sub_task_id, file_type);
CREATE INDEX idx_attachment_object ON attachment(bucket_name, object_key);
CREATE TRIGGER update_attachment_update_time BEFORE UPDATE ON attachment
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();
