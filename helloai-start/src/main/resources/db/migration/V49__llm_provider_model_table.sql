-- ============================================================
-- V49__llm_provider_model_table.sql
-- 用途：LLM Provider 模型多选配置（Trae式模型管理）
-- 背景：
--   当前 llm_provider 表仅支持单 default_model，无法满足 Trae 式多模型管理需求。
--   用户要求：每个 Provider 可配置多个可用模型，Agent 注册时从已配置模型中选择。
-- 机制：
--   - 新增 llm_provider_model 关联表：Provider 与模型的多对多关系
--   - 支持每个 Provider 设置一个默认模型（is_default=1）
--   - 内置 Provider 模型列表固定，只可选不可改
--   - 自定义 Provider 支持任意模型名称
-- 约束：
--   - 同一 Provider 下模型名称唯一（uk_provider_model）
--   - 每个 Provider 必须有一个默认模型（应用层校验）
--   - 删除 Provider 时级联删除模型（ON DELETE CASCADE）
-- ============================================================

CREATE TABLE IF NOT EXISTS llm_provider_model (
    id              BIGINT NOT NULL,
    deleted         SMALLINT NOT NULL DEFAULT 0,
    create_by       VARCHAR(64) NOT NULL DEFAULT '',
    update_by       VARCHAR(64) NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark          VARCHAR(255) DEFAULT NULL,
    -- 业务字段
    provider_id     BIGINT NOT NULL,           -- 关联 llm_provider.id
    provider_code   VARCHAR(64) NOT NULL,      -- 冗余 provider_code，便于查询
    model_name      VARCHAR(128) NOT NULL,     -- 模型名称，如 deepseek-v4-flash
    is_default      SMALLINT NOT NULL DEFAULT 0,  -- 是否默认模型：1=是，0=否
    enabled         SMALLINT NOT NULL DEFAULT 1,  -- 启用/禁用：1=启用，0=禁用
    sort_order      INT NOT NULL DEFAULT 0,    -- 列表排序（数值越小越靠前）
    CONSTRAINT pk_llm_provider_model PRIMARY KEY (id),
    CONSTRAINT uk_provider_model UNIQUE (provider_id, model_name),
    CONSTRAINT fk_provider_model_provider FOREIGN KEY (provider_id)
        REFERENCES llm_provider(id) ON DELETE CASCADE
);

-- 索引：按 Provider 查询启用模型
CREATE INDEX IF NOT EXISTS idx_provider_model_enabled
    ON llm_provider_model(provider_id, enabled)
    WHERE deleted = 0;

-- 索引：按 Provider 查询默认模型
CREATE INDEX IF NOT EXISTS idx_provider_model_default
    ON llm_provider_model(provider_id, is_default)
    WHERE deleted = 0 AND is_default = 1;

-- 索引：按 provider_code 查询（Agent 注册时校验）
CREATE INDEX IF NOT EXISTS idx_provider_model_code
    ON llm_provider_model(provider_code, model_name, enabled)
    WHERE deleted = 0;

-- 触发器：自动更新 update_time
DROP TRIGGER IF EXISTS update_llm_provider_model_update_time ON llm_provider_model;
CREATE TRIGGER update_llm_provider_model_update_time BEFORE UPDATE ON llm_provider_model
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

-- ============================================================
-- 种子数据：内置 Provider 模型（2026-08-14 官网更新）
-- ============================================================

-- DeepSeek 模型（deepseek-v4-flash 为默认）
-- 注意：PostgreSQL 的 INSERT...SELECT 中所有行基于同一快照求值，
-- 标量子查询 (SELECT MAX(id)) 对每行结果相同，必须把 ROW_NUMBER() 放外层 SELECT 拼接生成递增 id。
INSERT INTO llm_provider_model (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, m.is_default, 1, m.sort_order, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('deepseek-v4-flash', 1, 10),
    ('deepseek-v4-pro',   0, 20)
) AS m(model_name, is_default, sort_order)
WHERE p.provider_code = 'deepseek' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) DO NOTHING;

-- Moonshot (Kimi) 模型（kimi-k2.5 为默认）
INSERT INTO llm_provider_model (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, m.is_default, 1, m.sort_order, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('kimi-k3',                  0, 10),
    ('kimi-k2.7-code',           0, 20),
    ('kimi-k2.7-code-highspeed', 0, 30),
    ('kimi-k2.6',                0, 40),
    ('kimi-k2.5',                1, 50)
) AS m(model_name, is_default, sort_order)
WHERE p.provider_code = 'moonshot' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) DO NOTHING;

-- DashScope (通义千问) 模型（qwen3.7-plus 为默认）
INSERT INTO llm_provider_model (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, m.is_default, 1, m.sort_order, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('qwen3.8-Max',   0, 10),
    ('qwen3.7-plus',  1, 20),
    ('qwen3.6-Flash', 0, 30)
) AS m(model_name, is_default, sort_order)
WHERE p.provider_code = 'dashscope' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) DO NOTHING;

-- MiniMax 模型（MiniMax-M2.5 为默认，保持现有）
INSERT INTO llm_provider_model (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, m.is_default, 1, m.sort_order, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('MiniMax-M2.5', 1, 10)
) AS m(model_name, is_default, sort_order)
WHERE p.provider_code = 'minimax' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) DO NOTHING;

-- ============================================================
-- 现有 default_model 迁移（兼容老数据）
-- 对于没有模型记录的 Provider，将其 default_model 迁移为默认模型
-- ============================================================

INSERT INTO llm_provider_model (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id,
    p.provider_code,
    p.default_model,
    1,  -- 设为默认
    1,  -- 启用
    100, -- 排序靠后
    'system', 'system'
FROM llm_provider p
WHERE p.deleted = 0
  AND p.default_model IS NOT NULL
  AND p.default_model != ''
  AND NOT EXISTS (
      SELECT 1 FROM llm_provider_model m
      WHERE m.provider_id = p.id AND m.deleted = 0
  )
ON CONFLICT (provider_id, model_name) DO NOTHING;

-- ============================================================
-- 字段注释
-- ============================================================

COMMENT ON TABLE llm_provider_model IS 'LLM Provider 模型配置表（V49 新增）：每个 Provider 可配置多个可用模型，必须有一个默认模型';
COMMENT ON COLUMN llm_provider_model.provider_id IS '关联 llm_provider.id，外键级联删除';
COMMENT ON COLUMN llm_provider_model.provider_code IS '冗余 provider_code，便于按 code 查询（Agent 注册时校验）';
COMMENT ON COLUMN llm_provider_model.model_name IS '模型名称，如 deepseek-v4-flash / kimi-k2.5 / qwen3.7-plus';
COMMENT ON COLUMN llm_provider_model.is_default IS '是否默认模型：1=是，0=否。每个 Provider 必须有一个默认模型（应用层校验）';
COMMENT ON COLUMN llm_provider_model.enabled IS '启用/禁用：1=启用，0=禁用。禁用的模型不在 Agent 注册时展示';
COMMENT ON COLUMN llm_provider_model.sort_order IS '列表排序（数值越小越靠前）';

-- ============================================================
-- 验证
-- ============================================================

DO $$
DECLARE
    provider_count INT;
    model_count INT;
BEGIN
    SELECT COUNT(*) INTO provider_count FROM llm_provider WHERE deleted = 0;
    SELECT COUNT(*) INTO model_count FROM llm_provider_model WHERE deleted = 0;
    RAISE NOTICE '[V49] llm_provider_model 表已创建，% 个 Provider，% 条模型记录', provider_count, model_count;
END $$;
