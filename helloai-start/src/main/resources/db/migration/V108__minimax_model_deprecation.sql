-- ============================================================
-- V108: MiniMax 模型下线迁移（M2.5 / M2.7 全系列 → MiniMax-M3）
-- ------------------------------------------------------------
-- 背景：MiniMax-M2.5 / MiniMax-M2.7（含 -highspeed 变体）即将下线，
--   调用会被拒（模型不存在 / 停用）。参考项目当前 MiniMax 现役模型为
--   MiniMax-M3（512K 上下文）。
-- 参考实证（2026-10-13 实测，均带 path:line）：
--   - magpie: internal/provider/workbuddy.go:91
--       {ID: "minimax-m3", Name: "MiniMax-M3", Context: 512_000}
--   - magpie: internal/provider/factory.go:625
--       {"minimax-m3", "MiniMax M3", Chat, "fireworks", 512000, 64000, ["high"], true}
--     （同表内 minimax-m2.7 末位为 false ⇒ 已非现役）
--   - magpie: internal/provider/presets.go:273,281  minimax-m3 与 minimax-m2.7 并列（m3 更新）
--   - magpie: internal/provider/testdata/commandcode_models.json
--       MiniMaxAI/MiniMax-M3 / -M2.7 / -M2.5（M3 为最新，M2.x 为上一代）
--   - octop-harness: src/octop_harness/providers/provider_template.json:267  minimax-m3
--   - deepseek-harness: apps/web/tests/expected/models-settings/model-picker.expected.md:15
--       checkbox "MiniMax-M3" [checked]
-- 已纳入（2026-10-13 用户提供 MiniMax 官方口径，二次确认后并入本迁移）：
--   - MiniMax-M3.1-Flash-Preview：原生多模态、1M 上下文的 Frontier Coding 模型，思考深度可调
--     （初版 V108 曾因「仅现于官方错误串、无参考项目启用」判为 Preview 而不纳入；
--      用户确认为现役可选模型 ⇒ 并入本迁移，见处置第 5 条）
-- 仍未纳入：
--   - -highspeed 变体：参考项目无 M3-highspeed 记录（M2.5/M2.7 时代才有）。
-- 本迁移完成五件事（处置口径与 V61 moonshot 下线一致）：
--   1) llm_provider.default_model：MiniMax-M2.5 → MiniMax-M3
--      （该列优先于 sys_config / yml，是密钥验证与 Agent 注册的实际取值源
--        —— 不修则「验证失败 / 注册被拒」复发，同 V61/V101 的 404 命门）
--   2) 模型目录补 MiniMax-M3 行（V49 未种，防御性补齐）
--   3) M2.5 / M2.7 全系列（含 -highspeed）禁用并让出默认位
--   4) MiniMax-M3 提升为默认位，并确保能力列口径与 minimax 现有行一致
--   5) 追加 MiniMax-M3.1-Flash-Preview（enabled=1，非默认）⇒ minimax 两个可选模型
-- 幂等：全部为条件 UPDATE + ON CONFLICT DO NOTHING，重复执行无副作用；
--   不修改任何已 apply 的 V*.sql（Flyway 校验和不可变，CODE_STYLE §16）；
--   不动 Agent 存量 model_type（存量排查口径随迭代记录交付人工核实，同 V61）。
-- 注意：
--   - V50 已把物理唯一约束 uk_provider_model 改为部分唯一索引
--     uk_provider_model_active (provider_id, model_name) WHERE deleted = 0，
--     ON CONFLICT 列推断必须带一致的 WHERE 谓词，否则报 42P10
--     （there is no unique or exclusion constraint matching the ON CONFLICT specification）。
--   - V49 另有部分唯一索引 (provider_id, is_default) WHERE deleted = 0 AND is_default = 1
--     ⇒ 同一 provider 同时只能有一行 is_default=1，**必须先降旧行再升新行**，否则插入即冲突。
--   - 新行必须显式写能力列：V52 的能力回填已执行过，不会对后加的行生效，
--     走列默认值会丢 web-search（minimax 现役口径为含 web-search）。
-- ============================================================

-- 1) Provider 级默认模型：仅当仍指向即将下线的 M2.5 / M2.7 时才覆盖（保留人工改过的值）
UPDATE llm_provider
SET default_model = 'MiniMax-M3',
    update_by = 'V108-minimax-deprecation'
WHERE provider_code = 'minimax'
  AND deleted = 0
  AND (default_model IS NULL
       OR default_model LIKE 'MiniMax-M2.5%'
       OR default_model LIKE 'MiniMax-M2.7%');

-- 2) 模型目录：补 MiniMax-M3 行（is_default 先置 0，待第 3 步降完旧默认位后再提升，
--    避免撞 (provider_id, is_default) 部分唯一索引）。能力列按 minimax 现役口径显式写入。
INSERT INTO llm_provider_model (id, provider_id, provider_code, model_name, is_default, enabled, sort_order,
                                capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + 1,
    p.id, p.provider_code, 'MiniMax-M3', 0, 1, 5,
    '["thinking"]', '["shell","code-review","web-search"]', 'system', 'system'
FROM llm_provider p
WHERE p.provider_code = 'minimax' AND p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM llm_provider_model m
      WHERE m.provider_id = p.id AND m.model_name = 'MiniMax-M3')
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- 3) 让出默认位并禁用：M2.5 / M2.7 全系列（含 -highspeed；V49 只种了 M2.5，
--    此处按前缀防御存量手工添加过的场景）
UPDATE llm_provider_model
SET is_default = 0,
    enabled = 0,
    update_by = 'V108-minimax-deprecation'
WHERE provider_code = 'minimax'
  AND deleted = 0
  AND (model_name LIKE 'MiniMax-M2.5%'
       OR model_name LIKE 'MiniMax-M2.7%');

-- 4) 提升 MiniMax-M3 为默认位，并确保能力列口径一致（含 V108 前手工建过该行的场景）
UPDATE llm_provider_model
SET is_default = 1,
    enabled = 1,
    capability_skills = '["thinking"]',
    available_optional_skills = '["shell","code-review","web-search"]',
    update_by = 'V108-minimax-deprecation'
WHERE provider_code = 'minimax'
  AND model_name = 'MiniMax-M3'
  AND deleted = 0;

-- 5) 追加 MiniMax-M3.1-Flash-Preview（enabled=1，非默认）—— minimax 第二个可选模型。
--    能力列必须显式写入：V52 的能力回填已执行完，不会对后加行生效，
--    否则落列默认值会丢 web-search。
INSERT INTO llm_provider_model (id, provider_id, provider_code, model_name, is_default, enabled, sort_order,
                                capability_skills, available_optional_skills, create_by, update_by)
SELECT
    (SELECT COALESCE(MAX(id), 0) FROM llm_provider_model) + 1,
    p.id, p.provider_code, 'MiniMax-M3.1-Flash-Preview', 0, 1, 6,
    '["thinking"]', '["shell","code-review","web-search"]', 'system', 'system'
FROM llm_provider p
WHERE p.provider_code = 'minimax' AND p.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM llm_provider_model m
      WHERE m.provider_id = p.id AND m.model_name = 'MiniMax-M3.1-Flash-Preview' AND m.deleted = 0)
ON CONFLICT (provider_id, model_name) WHERE deleted = 0 DO NOTHING;

-- ============================================================
-- 验证：minimax 恰有 1 条 is_default=1 且为 MiniMax-M3；M2.5/M2.7 全禁用；
--       MiniMax-M3 与 MiniMax-M3.1-Flash-Preview 各 1 条且均已启用
-- ============================================================
DO $$
DECLARE
    default_cnt   INT;
    default_name  TEXT;
    legacy_active INT;
    m3_exists     INT;
    m31_exists    INT;
    m31_enabled   INT;
BEGIN
    SELECT COUNT(*), MAX(model_name) INTO default_cnt, default_name
      FROM llm_provider_model
     WHERE deleted = 0 AND enabled = 1 AND is_default = 1 AND provider_code = 'minimax';

    SELECT COUNT(*) INTO legacy_active
      FROM llm_provider_model
     WHERE deleted = 0 AND provider_code = 'minimax'
       AND (model_name LIKE 'MiniMax-M2.5%' OR model_name LIKE 'MiniMax-M2.7%')
       AND (enabled = 1 OR is_default = 1);

    SELECT COUNT(*) INTO m3_exists
      FROM llm_provider_model
     WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M3';

    SELECT COUNT(*), COALESCE(SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END), 0)
      INTO m31_exists, m31_enabled
      FROM llm_provider_model
     WHERE deleted = 0 AND provider_code = 'minimax' AND model_name = 'MiniMax-M3.1-Flash-Preview';

    IF default_cnt <> 1 OR default_name <> 'MiniMax-M3' THEN
        RAISE EXCEPTION '[V108] minimax 默认位异常：cnt=% name=%（期望 1 条 MiniMax-M3）', default_cnt, default_name;
    END IF;
    IF legacy_active <> 0 THEN
        RAISE EXCEPTION '[V108] 仍有 % 条 M2.5/M2.7 行处于启用或默认位', legacy_active;
    END IF;
    IF m3_exists <> 1 THEN
        RAISE EXCEPTION '[V108] MiniMax-M3 行缺失（cnt=%）', m3_exists;
    END IF;
    IF m31_exists <> 1 OR m31_enabled <> 1 THEN
        RAISE EXCEPTION '[V108] MiniMax-M3.1-Flash-Preview 异常：cnt=% enabled=%（期望 1 条且启用）', m31_exists, m31_enabled;
    END IF;

    RAISE NOTICE '[V108] minimax 默认位已切至 MiniMax-M3；M2.5/M2.7 已禁用；已追加 MiniMax-M3.1-Flash-Preview';
END $$;
