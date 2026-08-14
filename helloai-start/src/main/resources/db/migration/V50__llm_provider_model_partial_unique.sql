-- ============================================================
-- V50__llm_provider_model_partial_unique.sql
-- 用途：修复模型软删后无法重建同名模型的唯一约束冲突（V49 遗留）
-- 背景：
--   V49 建表时定义 uk_provider_model UNIQUE (provider_id, model_name) 为物理唯一约束，
--   但应用层删除走 MyBatis-Plus 逻辑删除（UPDATE deleted=1）。
--   后果：删除模型 m1（软删）后再次添加同名 m1 时，
--   save() 物理插入违反唯一约束 -> duplicate key -> 500；
--   同样 saveAllModels 幂等重跑（先 remove 软删再插入同名模型）也会崩溃。
-- 方案：
--   删除物理唯一约束，改为部分唯一索引（仅约束 deleted=0 的活跃记录），
--   与逻辑删除模式对齐：软删记录不占唯一性，同名模型可重新添加。
-- 注意：
--   V49 种子数据的 ON CONFLICT (provider_id, model_name) 依赖原约束，
--   但 V49 已执行且迁移只跑一次，不受影响。
-- ============================================================

ALTER TABLE llm_provider_model DROP CONSTRAINT IF EXISTS uk_provider_model;

-- 部分唯一索引：仅活跃记录 (deleted=0) 要求 provider+model 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_model_active
    ON llm_provider_model (provider_id, model_name)
    WHERE deleted = 0;

-- ============================================================
-- 验证
-- ============================================================

DO $$
DECLARE
    idx_count INT;
    dup_count INT;
BEGIN
    SELECT COUNT(*) INTO idx_count
    FROM pg_indexes
    WHERE tablename = 'llm_provider_model' AND indexname = 'uk_provider_model_active';
    SELECT COUNT(*) INTO dup_count FROM (
        SELECT provider_id, model_name
        FROM llm_provider_model
        WHERE deleted = 0
        GROUP BY provider_id, model_name
        HAVING COUNT(*) > 1
    ) d;
    RAISE NOTICE '[V50] 部分唯一索引已就绪: idx=% , 活跃重复组=%', idx_count, dup_count;
END $$;
