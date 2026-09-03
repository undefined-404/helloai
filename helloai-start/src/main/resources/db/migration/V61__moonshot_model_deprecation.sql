-- ============================================================
-- V61: Moonshot (Kimi) 模型下线迁移（2026-08-31 官方通知）
-- ------------------------------------------------------------
-- 背景：moonshot-v1 全系列（含 moonshot-v1-8k）与 kimi-k2.5 已于
--   2026-08-31 正式下线，调用返回 404（模型不存在）。官方建议迁移至
--   kimi-k3 等最新模型。
-- 本迁移完成三件事：
--   1) llm_provider.default_model：moonshot 仍指向已下线的
--      moonshot-v1-8k（V46 种子），且该列优先于 sys_config / yml，
--      是「验证失败（HTTP 404）」的直接根因 → 切到 kimi-k3
--   2) llm_provider_model：kimi-k2.5 禁用并让出默认位，默认切 kimi-k3
--   3) moonshot-v1-* 历史残留行（如有）禁用
-- 幂等：全部为条件 UPDATE，重复执行无副作用；不动 Agent 存量配置，
--   存量 model_type 的排查口径随迭代记录交付人工核实。
-- ============================================================

-- 1) Provider 级默认模型：仅当仍是已下线模型时才覆盖（保留人工改过的值）
UPDATE llm_provider
SET default_model = 'kimi-k3',
    update_by = 'V61-moonshot-deprecation'
WHERE provider_code = 'moonshot'
  AND deleted = 0
  AND (default_model IS NULL
       OR default_model LIKE 'moonshot-v1-%'
       OR default_model = 'kimi-k2.5');

-- 2) 模型目录：新增场景下 kimi-k3 可能尚未建行（V49 已种，防御补齐）
-- 注意：V50 已把物理唯一约束 uk_provider_model 改为部分唯一索引
--   uk_provider_model_active (provider_id, model_name) WHERE deleted = 0，
--   ON CONFLICT 列推断必须带与索引一致的 WHERE 谓词，否则报 42P10
--   （there is no unique or exclusion constraint matching the ON CONFLICT specification）。
INSERT INTO llm_provider_model (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + 1,
    p.id, p.provider_code, 'kimi-k3', 0, 1, 10, 'system', 'system'
FROM llm_provider p
WHERE p.provider_code = 'moonshot' AND p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM llm_provider_model m
      WHERE m.provider_id = p.id AND m.model_name = 'kimi-k3')
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- 3) 默认位切换：kimi-k2.5（已下线）让出默认 → kimi-k3；同 provider 唯一默认
UPDATE llm_provider_model
SET is_default = 0,
    enabled = 0,
    update_by = 'V61-moonshot-deprecation'
WHERE provider_code = 'moonshot'
  AND model_name IN ('kimi-k2.5')
  AND deleted = 0;

UPDATE llm_provider_model
SET is_default = 1,
    enabled = 1,
    update_by = 'V61-moonshot-deprecation'
WHERE provider_code = 'moonshot'
  AND model_name = 'kimi-k3'
  AND deleted = 0;

-- 4) moonshot-v1-* 历史残留行（V49 未种，防御存量手工添加过的场景）
UPDATE llm_provider_model
SET enabled = 0,
    is_default = 0,
    update_by = 'V61-moonshot-deprecation'
WHERE provider_code = 'moonshot'
  AND model_name LIKE 'moonshot-v1-%'
  AND deleted = 0;
