-- ============================================================
-- V3__sub_task_fields.sql
-- 补充子任务字段（交付物/验收标准/优先级/返工计数/完成时间）
-- ============================================================

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS deliverable     TEXT,
    ADD COLUMN IF NOT EXISTS acceptance      TEXT,
    ADD COLUMN IF NOT EXISTS priority        VARCHAR(10) NOT NULL DEFAULT 'MEDIUM',
    ADD COLUMN IF NOT EXISTS rework_count    INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS completed_at    TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_sub_task_priority ON sub_task(priority, deleted) WHERE deleted = 0;

COMMENT ON COLUMN sub_task.deliverable IS '交付物描述';
COMMENT ON COLUMN sub_task.acceptance IS '验收标准';
COMMENT ON COLUMN sub_task.priority IS '优先级：HIGH / MEDIUM / LOW';
COMMENT ON COLUMN sub_task.rework_count IS '返工次数';
COMMENT ON COLUMN sub_task.completed_at IS '完成时间';
