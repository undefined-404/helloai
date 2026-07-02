-- ============================================================
-- HelloAI V5: 子任务评分因子
-- ============================================================

ALTER TABLE sub_task
ADD COLUMN IF NOT EXISTS score_factors JSONB,
ADD COLUMN IF NOT EXISTS composite_score INT,
ADD COLUMN IF NOT EXISTS score_grade VARCHAR(4);

CREATE INDEX IF NOT EXISTS idx_sub_task_score ON sub_task((score_factors->>'grade'))
    WHERE score_factors IS NOT NULL;
