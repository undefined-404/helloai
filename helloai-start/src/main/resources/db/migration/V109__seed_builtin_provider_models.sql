-- ============================================================
-- V109__seed_builtin_provider_models.sql
-- 用途：M-3「模型目录种子」——用 models.dev 快照替代手工静态行，
--   并把 M-1 首波 5 个内置预置（openai/anthropic/xai/mistral/groq）
--   由「运行时建档」改为「种子建档」。
-- ------------------------------------------------------------
-- 数据源：https://models.dev/api.json（与参考项目 magpie 同源，
--   internal/catalog/catalog.go:397）；本迁移内联**裁剪后**的快照结果，
--   不在运行时拉网（保持迁移可离线、可复现）。
-- 播种口径（用户 2026-10-14 裁定 + 一项收紧）：
--   「text 输出 + context>0 + status≠deprecated」全量；
--   ★收紧：再加 `tool_call=true`（models.dev 客观字段）——不加会混入
--     ASR / OCR / 翻译 / realtime 等**非对话**模型（如 qwen3-asr-flash、
--     qwen-vl-ocr、qwen-mt-turbo、qwen3-livetranslate-flash-realtime），
--     它们进 Agent 模型选择器即功能缺陷。实测由 164 收紧到 149 条。
--   每个 provider 恰 1 条 is_default=1，且恒等于前端预置 defaultModel。
-- ★默认位例外：deepseek-v4-flash 在 models.dev 已标 deprecated，
--   本批**不改默认位**（另立决策再动四方载体），仅纳入清单并在此标注。
-- ------------------------------------------------------------
-- 幂等：ON CONFLICT 均带 WHERE deleted=0（V51/V50 部分唯一索引）；
--   模型行一律以 is_default=0 插入，默认位由「先降后升」两步收敛，
--   避免撞 V49 的部分唯一索引 (provider_id,is_default) WHERE deleted=0 AND is_default=1。
-- ★能力列显式写：V52 的回填只对当时已有的行生效，后加行走列默认会**丢 web-search**；
--   本迁移按 V52 已确立的 per-provider 口径显式写（deepseek 无 web-search，
--   moonshot/dashscope/minimax 有；新增 5 家未调研 ⇒ 取无 web-search 口径）。
--   逐模型的能力精化（thinking/image/web-search 由 reasoning/modality 推导）属 M-4。
-- 注意：不修改任何已 apply 的 V*.sql（Flyway 校验和不可变）。
-- ============================================================

-- ============================================================
-- 1) 为 M-1 首波 5 个内置预置补 llm_provider 行（builtin=1）
--    口径 = 前端预置 providerCatalog.ts（协议 / 基址 / 默认模型）
-- ============================================================

INSERT INTO llm_provider
    (id, provider_code, provider_name, protocol_type, base_url, default_model, enabled, builtin, sort_order, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider) + ROW_NUMBER() OVER (),
    v.code, v.name, v.proto, v.base, v.model, 1, 1, v.sort, 'system', 'system'
FROM (VALUES
    ('openai', 'OpenAI', 'OPENAI_COMPATIBLE', 'https://api.openai.com', 'gpt-4o', 50),
    ('anthropic', 'Anthropic', 'ANTHROPIC_COMPATIBLE', 'https://api.anthropic.com', 'claude-sonnet-4-5', 60),
    ('xai', 'xAI', 'OPENAI_COMPATIBLE', 'https://api.x.ai', 'grok-4.7', 70),
    ('mistral', 'Mistral AI', 'OPENAI_COMPATIBLE', 'https://api.mistral.ai', 'mistral-large-latest', 80),
    ('groq', 'Groq', 'OPENAI_COMPATIBLE', 'https://api.groq.com/openai', 'llama-3.3-70b-versatile', 90)
) AS v(code, name, proto, base, model, sort)
WHERE NOT EXISTS (SELECT 1 FROM llm_provider p WHERE p.provider_code = v.code AND p.deleted = 0)
ON CONFLICT (provider_code) WHERE deleted = 0 DO NOTHING;

-- ============================================================
-- 2) 播种 9 个内置 provider 的模型清单（models.dev 快照裁剪）
--    全行 is_default=0；默认位见第 4 步
-- ============================================================

-- deepseek（catalog=deepseek，3 条；默认位 deepseek-v4-flash）
INSERT INTO llm_provider_model
    (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, 0, 1, m.sort_order, m.cap::jsonb, m.opt::jsonb, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('deepseek-v4-flash', 10, '["thinking"]', '["shell","code-review"]'),
    ('deepseek-flash', 110, '["thinking"]', '["shell","code-review"]'),
    ('deepseek-v4-pro', 120, '["thinking"]', '["shell","code-review"]')
) AS m(model_name, sort_order, cap, opt)
WHERE p.provider_code = 'deepseek' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- moonshot（catalog=moonshotai，4 条；默认位 kimi-k3）
INSERT INTO llm_provider_model
    (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, 0, 1, m.sort_order, m.cap::jsonb, m.opt::jsonb, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('kimi-k3', 10, '["thinking"]', '["shell","code-review","web-search"]'),
    ('kimi-k2.7-code-highspeed', 110, '["thinking"]', '["shell","code-review","web-search"]'),
    ('kimi-k2.7-code', 120, '["thinking"]', '["shell","code-review","web-search"]'),
    ('kimi-k2.6', 130, '["thinking"]', '["shell","code-review","web-search"]')
) AS m(model_name, sort_order, cap, opt)
WHERE p.provider_code = 'moonshot' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- minimax（catalog=minimax，7 条；默认位 MiniMax-M3）
INSERT INTO llm_provider_model
    (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, 0, 1, m.sort_order, m.cap::jsonb, m.opt::jsonb, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('MiniMax-M3', 10, '["thinking"]', '["shell","code-review","web-search"]'),
    ('MiniMax-M2.7-highspeed', 110, '["thinking"]', '["shell","code-review","web-search"]'),
    ('MiniMax-M2.7', 120, '["thinking"]', '["shell","code-review","web-search"]'),
    ('MiniMax-M2.5-highspeed', 130, '["thinking"]', '["shell","code-review","web-search"]'),
    ('MiniMax-M2.5', 140, '["thinking"]', '["shell","code-review","web-search"]'),
    ('MiniMax-M2.1', 150, '["thinking"]', '["shell","code-review","web-search"]'),
    ('MiniMax-M2', 160, '["thinking"]', '["shell","code-review","web-search"]')
) AS m(model_name, sort_order, cap, opt)
WHERE p.provider_code = 'minimax' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- dashscope（catalog=alibaba，54 条；默认位 qwen3.7-plus）
INSERT INTO llm_provider_model
    (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, 0, 1, m.sort_order, m.cap::jsonb, m.opt::jsonb, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('qwen3.7-plus', 10, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.8-omni-flash', 110, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.8-flash', 120, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.8-max', 130, '["thinking"]', '["shell","code-review","web-search"]'),
    ('deepseek-v4-flash-0731', 140, '["thinking"]', '["shell","code-review","web-search"]'),
    ('kimi-k3', 150, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.7-flash', 160, '["thinking"]', '["shell","code-review","web-search"]'),
    ('glm-5.2', 170, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.7-max', 180, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.6-flash', 190, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.6-27b', 200, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.6-max-preview', 210, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.6-35b-a3b', 220, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.6-plus', 230, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.5-flash', 240, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.5-35b-a3b', 250, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.5-27b', 260, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.5-122b-a10b', 270, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.5-plus', 280, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3.5-397b-a17b', 290, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-vl-plus', 300, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-max', 310, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-omni-flash-realtime', 320, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-omni-flash', 330, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-next-80b-a3b-thinking', 340, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-next-80b-a3b-instruct', 350, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-coder-flash', 360, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen-flash', 370, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-coder-plus', 380, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen-omni-turbo-realtime', 390, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-vl-30b-a3b', 400, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-vl-235b-a22b', 410, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-coder-480b-a35b-instruct', 420, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-coder-30b-a3b-instruct', 430, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-8b', 440, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-32b', 450, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-235b-a22b', 460, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen3-14b', 470, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qvq-max', 480, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwq-plus', 490, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen-omni-turbo', 500, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen2-5-omni-7b', 510, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen-turbo', 520, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen2-5-vl-7b-instruct', 530, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen2-5-vl-72b-instruct', 540, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen2-5-7b-instruct', 550, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen2-5-72b-instruct', 560, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen2-5-32b-instruct', 570, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen2-5-14b-instruct', 580, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen-vl-max', 590, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen-max', 600, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen-vl-plus', 610, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen-plus', 620, '["thinking"]', '["shell","code-review","web-search"]'),
    ('qwen-plus-character-ja', 630, '["thinking"]', '["shell","code-review","web-search"]')
) AS m(model_name, sort_order, cap, opt)
WHERE p.provider_code = 'dashscope' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- openai（catalog=openai，34 条；默认位 gpt-4o）
INSERT INTO llm_provider_model
    (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, 0, 1, m.sort_order, m.cap::jsonb, m.opt::jsonb, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('gpt-4o', 10, '["thinking"]', '["shell","code-review"]'),
    ('gpt-6.1-sol', 110, '["thinking"]', '["shell","code-review"]'),
    ('gpt-6-sol', 120, '["thinking"]', '["shell","code-review"]'),
    ('gpt-6-luna', 130, '["thinking"]', '["shell","code-review"]'),
    ('gpt-6-astra', 140, '["thinking"]', '["shell","code-review"]'),
    ('gpt-daybreak-red-latest', 150, '["thinking"]', '["shell","code-review"]'),
    ('gpt-daybreak-blue-latest', 160, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.6-terra', 170, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.6-sol', 180, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.6-luna', 190, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.6', 200, '["thinking"]', '["shell","code-review"]'),
    ('gpt-realtime-2.1', 210, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.5-pro', 220, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.5', 230, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.4-nano', 240, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.4-mini', 250, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.4-pro', 260, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.4', 270, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.3-codex-spark', 280, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.3-codex', 290, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.2-pro', 300, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.2', 310, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5.1', 320, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5-pro', 330, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5-nano', 340, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5-mini', 350, '["thinking"]', '["shell","code-review"]'),
    ('gpt-5', 360, '["thinking"]', '["shell","code-review"]'),
    ('o3-pro', 370, '["thinking"]', '["shell","code-review"]'),
    ('o3', 380, '["thinking"]', '["shell","code-review"]'),
    ('gpt-4.1-mini', 390, '["thinking"]', '["shell","code-review"]'),
    ('gpt-4.1', 400, '["thinking"]', '["shell","code-review"]'),
    ('gpt-4o-2024-11-20', 410, '["thinking"]', '["shell","code-review"]'),
    ('gpt-4o-2024-08-06', 420, '["thinking"]', '["shell","code-review"]'),
    ('gpt-4o-mini', 430, '["thinking"]', '["shell","code-review"]')
) AS m(model_name, sort_order, cap, opt)
WHERE p.provider_code = 'openai' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- anthropic（catalog=anthropic，17 条；默认位 claude-sonnet-4-5）
INSERT INTO llm_provider_model
    (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, 0, 1, m.sort_order, m.cap::jsonb, m.opt::jsonb, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('claude-sonnet-4-5', 10, '["thinking"]', '["shell","code-review"]'),
    ('claude-haiku-5-5', 110, '["thinking"]', '["shell","code-review"]'),
    ('claude-sonnet-5-5', 120, '["thinking"]', '["shell","code-review"]'),
    ('claude-opus-5-5', 130, '["thinking"]', '["shell","code-review"]'),
    ('claude-fable-5-1', 140, '["thinking"]', '["shell","code-review"]'),
    ('claude-opus-5', 150, '["thinking"]', '["shell","code-review"]'),
    ('claude-sonnet-5', 160, '["thinking"]', '["shell","code-review"]'),
    ('claude-fable-5', 170, '["thinking"]', '["shell","code-review"]'),
    ('claude-opus-4-8', 180, '["thinking"]', '["shell","code-review"]'),
    ('claude-opus-4-7', 190, '["thinking"]', '["shell","code-review"]'),
    ('claude-sonnet-4-6', 200, '["thinking"]', '["shell","code-review"]'),
    ('claude-opus-4-6', 210, '["thinking"]', '["shell","code-review"]'),
    ('claude-opus-4-5-20251101', 220, '["thinking"]', '["shell","code-review"]'),
    ('claude-opus-4-5', 230, '["thinking"]', '["shell","code-review"]'),
    ('claude-haiku-4-5-20251001', 240, '["thinking"]', '["shell","code-review"]'),
    ('claude-haiku-4-5', 250, '["thinking"]', '["shell","code-review"]'),
    ('claude-sonnet-4-5-20250929', 260, '["thinking"]', '["shell","code-review"]')
) AS m(model_name, sort_order, cap, opt)
WHERE p.provider_code = 'anthropic' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- xai（catalog=xai，7 条；默认位 grok-4.7）
INSERT INTO llm_provider_model
    (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, 0, 1, m.sort_order, m.cap::jsonb, m.opt::jsonb, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('grok-4.7', 10, '["thinking"]', '["shell","code-review"]'),
    ('grok-4.6', 110, '["thinking"]', '["shell","code-review"]'),
    ('grok-4.5', 120, '["thinking"]', '["shell","code-review"]'),
    ('grok-4.3', 130, '["thinking"]', '["shell","code-review"]'),
    ('grok-build-0.1', 140, '["thinking"]', '["shell","code-review"]'),
    ('grok-4.20-0309-reasoning', 150, '["thinking"]', '["shell","code-review"]'),
    ('grok-4.20-0309-non-reasoning', 160, '["thinking"]', '["shell","code-review"]')
) AS m(model_name, sort_order, cap, opt)
WHERE p.provider_code = 'xai' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- mistral（catalog=mistral，17 条；默认位 mistral-large-latest）
INSERT INTO llm_provider_model
    (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, 0, 1, m.sort_order, m.cap::jsonb, m.opt::jsonb, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('mistral-large-latest', 10, '["thinking"]', '["shell","code-review"]'),
    ('mistral-large-4', 110, '["thinking"]', '["shell","code-review"]'),
    ('zai-glm-5-3', 120, '["thinking"]', '["shell","code-review"]'),
    ('mistral-medium-latest', 130, '["thinking"]', '["shell","code-review"]'),
    ('mistral-medium-2604', 140, '["thinking"]', '["shell","code-review"]'),
    ('mistral-small-latest', 150, '["thinking"]', '["shell","code-review"]'),
    ('mistral-small-2603', 160, '["thinking"]', '["shell","code-review"]'),
    ('ministral-8b-2512', 170, '["thinking"]', '["shell","code-review"]'),
    ('ministral-3b-2512', 180, '["thinking"]', '["shell","code-review"]'),
    ('ministral-14b-2512', 190, '["thinking"]', '["shell","code-review"]'),
    ('codestral-2508', 200, '["thinking"]', '["shell","code-review"]'),
    ('voxtral-small-latest', 210, '["thinking"]', '["shell","code-review"]'),
    ('voxtral-small-2507', 220, '["thinking"]', '["shell","code-review"]'),
    ('mistral-large-2512', 230, '["thinking"]', '["shell","code-review"]'),
    ('ministral-8b-latest', 240, '["thinking"]', '["shell","code-review"]'),
    ('ministral-3b-latest', 250, '["thinking"]', '["shell","code-review"]'),
    ('codestral-latest', 260, '["thinking"]', '["shell","code-review"]')
) AS m(model_name, sort_order, cap, opt)
WHERE p.provider_code = 'mistral' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- groq（catalog=groq，7 条；默认位 llama-3.3-70b-versatile）
INSERT INTO llm_provider_model
    (id, provider_id, provider_code, model_name, is_default, enabled, sort_order, capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + ROW_NUMBER() OVER (),
    p.id, p.provider_code, m.model_name, 0, 1, m.sort_order, m.cap::jsonb, m.opt::jsonb, 'system', 'system'
FROM llm_provider p
CROSS JOIN (VALUES
    ('llama-3.3-70b-versatile', 10, '["thinking"]', '["shell","code-review"]'),
    ('qwen/qwen3.8-27b', 110, '["thinking"]', '["shell","code-review"]'),
    ('qwen/qwen3.6-27b', 120, '["thinking"]', '["shell","code-review"]'),
    ('openai/gpt-oss-safeguard-20b', 130, '["thinking"]', '["shell","code-review"]'),
    ('openai/gpt-oss-20b', 140, '["thinking"]', '["shell","code-review"]'),
    ('openai/gpt-oss-120b', 150, '["thinking"]', '["shell","code-review"]'),
    ('llama-3.1-8b-instant', 160, '["thinking"]', '["shell","code-review"]')
) AS m(model_name, sort_order, cap, opt)
WHERE p.provider_code = 'groq' AND p.deleted = 0
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- ============================================================
-- 3) 让位：把「非预置默认位」的 is_default=1 降级（先降后升，避索引冲突）
-- ============================================================

UPDATE llm_provider_model m
SET is_default = 0
FROM (VALUES
    ('deepseek', 'deepseek-v4-flash'),
    ('moonshot', 'kimi-k3'),
    ('minimax', 'MiniMax-M3'),
    ('dashscope', 'qwen3.7-plus'),
    ('openai', 'gpt-4o'),
    ('anthropic', 'claude-sonnet-4-5'),
    ('xai', 'grok-4.7'),
    ('mistral', 'mistral-large-latest'),
    ('groq', 'llama-3.3-70b-versatile')
) AS d(code, model)
WHERE m.deleted = 0 AND m.provider_code = d.code AND m.is_default = 1 AND m.model_name <> d.model;

-- ============================================================
-- 4) 提升：把预置默认位设为 is_default=1 / enabled=1
-- ============================================================

UPDATE llm_provider_model m
SET is_default = 1, enabled = 1
FROM (VALUES
    ('deepseek', 'deepseek-v4-flash'),
    ('moonshot', 'kimi-k3'),
    ('minimax', 'MiniMax-M3'),
    ('dashscope', 'qwen3.7-plus'),
    ('openai', 'gpt-4o'),
    ('anthropic', 'claude-sonnet-4-5'),
    ('xai', 'grok-4.7'),
    ('mistral', 'mistral-large-latest'),
    ('groq', 'llama-3.3-70b-versatile')
) AS d(code, model)
WHERE m.deleted = 0 AND m.provider_code = d.code AND m.model_name = d.model;

-- ============================================================
-- 5) 验证：每个内置 provider 恰 1 条 is_default=1，且等于预置默认位
-- ============================================================

DO $$
DECLARE
    r          RECORD;
    d_cnt      INT;
    p_cnt      INT;
    bad        INT := 0;
    total_cnt  INT;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('deepseek', 'deepseek-v4-flash'),
            ('moonshot', 'kimi-k3'),
            ('minimax', 'MiniMax-M3'),
            ('dashscope', 'qwen3.7-plus'),
            ('openai', 'gpt-4o'),
            ('anthropic', 'claude-sonnet-4-5'),
            ('xai', 'grok-4.7'),
            ('mistral', 'mistral-large-latest'),
            ('groq', 'llama-3.3-70b-versatile')
        ) AS d(code, model)
    LOOP
        SELECT COUNT(*) INTO p_cnt FROM llm_provider WHERE provider_code = r.code AND deleted = 0;
        IF p_cnt <> 1 THEN
            RAISE EXCEPTION '[V109] provider % 应恰有 1 行，实为 %', r.code, p_cnt;
        END IF;
        SELECT COUNT(*) INTO d_cnt FROM llm_provider_model
            WHERE deleted = 0 AND provider_code = r.code AND is_default = 1;
        IF d_cnt <> 1 THEN
            RAISE EXCEPTION '[V109] provider % 的 is_default=1 行数应为 1，实为 %', r.code, d_cnt;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM llm_provider_model
            WHERE deleted = 0 AND provider_code = r.code AND is_default = 1 AND model_name = r.model) THEN
            RAISE EXCEPTION '[V109] provider % 的默认位不是预置模型 %', r.code, r.model;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM llm_provider_model
            WHERE deleted = 0 AND provider_code = r.code AND model_name = r.model) THEN
            RAISE EXCEPTION '[V109] provider % 缺默认位模型行 %', r.code, r.model;
        END IF;
        bad := bad + 1;
    END LOOP;
    SELECT COUNT(*) INTO total_cnt FROM llm_provider_model WHERE deleted = 0;
    RAISE NOTICE '[V109] 已校验 % 个内置 provider；llm_provider_model 活跃行合计 %', bad, total_cnt;
END $$;

