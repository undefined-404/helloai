-- =========================================================
-- V59：llm_provider 增加计费类型字段 billing_type
--   - 与"添加模型"弹窗的类型字段对应：API_KEY=按量付费（默认）
--   - TOKEN_PLAN / CODING_PLAN 为预留枚举值，当前应用层仅放行 API_KEY
-- =========================================================

ALTER TABLE llm_provider ADD COLUMN IF NOT EXISTS billing_type VARCHAR(32) NOT NULL DEFAULT 'API_KEY';

COMMENT ON COLUMN llm_provider.billing_type IS '计费类型：API_KEY=按量付费（默认）；TOKEN_PLAN / CODING_PLAN 预留，当前暂不支持';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'llm_provider' AND column_name = 'billing_type'
    ) THEN
        RAISE NOTICE 'V59 OK: llm_provider.billing_type 已就位（默认 API_KEY）';
    ELSE
        RAISE EXCEPTION 'V59 FAIL: llm_provider.billing_type 缺失';
    END IF;
END $$;
