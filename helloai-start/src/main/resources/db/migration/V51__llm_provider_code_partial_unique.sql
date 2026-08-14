-- ============================================================
-- V51__llm_provider_code_partial_unique.sql
-- 用途：修复 Provider 软删后无法重建同名 provider_code 的唯一约束冲突（与 V50 同源）
-- 背景：
--   llm_provider 的 uk_llm_provider_code UNIQUE (provider_code) 是物理唯一约束，
--   但应用层删除走逻辑删除（UPDATE deleted=1）。
--   后果：删除自定义 Provider（软删）后再次创建同 code 的 Provider 时，
--   INSERT 违反唯一约束 -> duplicate key -> 500。
-- 方案：
--   删除物理唯一约束，改为部分唯一索引（仅约束 deleted=0 的活跃记录），
--   与逻辑删除模式对齐：软删记录不占唯一性，同 code Provider 可重新创建。
-- ============================================================

ALTER TABLE llm_provider DROP CONSTRAINT IF EXISTS uk_llm_provider_code;

-- 部分唯一索引：仅活跃记录 (deleted=0) 要求 provider_code 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_provider_code_active
    ON llm_provider (provider_code)
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
    WHERE tablename = 'llm_provider' AND indexname = 'uk_llm_provider_code_active';
    SELECT COUNT(*) INTO dup_count FROM (
        SELECT provider_code
        FROM llm_provider
        WHERE deleted = 0
        GROUP BY provider_code
        HAVING COUNT(*) > 1
    ) d;
    RAISE NOTICE '[V51] provider_code 部分唯一索引已就绪: idx=% , 活跃重复组=%', idx_count, dup_count;
END $$;
