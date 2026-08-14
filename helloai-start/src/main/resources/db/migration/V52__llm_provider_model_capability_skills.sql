-- ============================================================
-- V52__llm_provider_model_capability_skills.sql
-- 用途：Agent 技能按模型能力驱动（plan 2182376f 完善版，D1=A 表驱动）
-- 背景：
--   Agent 注册内部 LLM（accessType=API_KEY_LLM）时，技能区需要按所选模型的
--   实际能力渲染「模型能力（锁定）」「可选项（白名单）」两段，后端校验防越界。
--   能力映射以表驱动落地：llm_provider_model 加两列 JSONB，与 V49 模型清单同源，
--   避免新建静态模板类造成两套事实源漂移。
-- 机制：
--   - capability_skills：模型能力锁定技能（注册/编辑时强制追加，不可取消）
--   - available_optional_skills：模型可扩展技能白名单（前端仅展示此集合，后端校验）
--   - 内置 11 个模型种子回填：deepseek 两模型无 web-search，其余 9 模型含 web-search
-- 约束：
--   - 新模型（无回填）使用列默认值：capability=["thinking"]、available=["shell","code-review"]
--   - 删除模型不清理能力列（随行删除），不单独建索引（按主键/唯一键查询）
-- ============================================================

ALTER TABLE llm_provider_model
    ADD COLUMN capability_skills JSONB NOT NULL DEFAULT '["thinking"]',
    ADD COLUMN available_optional_skills JSONB NOT NULL DEFAULT '["shell","code-review"]';

-- ============================================================
-- 种子回填：内置 Provider 模型能力（2026-08-14 官网调研，plan 2182376f §三 映射表）
-- deepseek 两模型保持列默认值（available = [shell, code-review]，无 web-search）
-- ============================================================

-- Moonshot (Kimi) 5 模型：全部支持联网检索
UPDATE llm_provider_model
SET available_optional_skills = '["shell","code-review","web-search"]'
WHERE deleted = 0 AND provider_code = 'moonshot';

-- DashScope (通义千问) 3 模型：全部支持联网检索
UPDATE llm_provider_model
SET available_optional_skills = '["shell","code-review","web-search"]'
WHERE deleted = 0 AND provider_code = 'dashscope';

-- MiniMax 1 模型：支持联网检索
UPDATE llm_provider_model
SET available_optional_skills = '["shell","code-review","web-search"]'
WHERE deleted = 0 AND provider_code = 'minimax';

-- ============================================================
-- 字段注释
-- ============================================================

COMMENT ON COLUMN llm_provider_model.capability_skills IS '模型能力锁定技能（JSONB 数组）：注册/编辑 Agent 时强制追加，不可取消。内置模型默认 ["thinking"]';
COMMENT ON COLUMN llm_provider_model.available_optional_skills IS '模型可扩展技能白名单（JSONB 数组）：注册时前端仅展示此集合，后端 validateAgentSkills 校验。内置模型默认 ["shell","code-review"]';

-- ============================================================
-- 验证
-- ============================================================

DO $$
DECLARE
    deepseek_cnt INT;
    other_cnt INT;
    total_cnt INT;
BEGIN
    SELECT COUNT(*) INTO deepseek_cnt FROM llm_provider_model
        WHERE deleted = 0 AND provider_code = 'deepseek'
          AND available_optional_skills::text = '["shell", "code-review"]';
    SELECT COUNT(*) INTO other_cnt FROM llm_provider_model
        WHERE deleted = 0 AND provider_code IN ('moonshot', 'dashscope', 'minimax')
          AND available_optional_skills::text = '["shell", "code-review", "web-search"]';
    SELECT COUNT(*) INTO total_cnt FROM llm_provider_model WHERE deleted = 0;
    RAISE NOTICE '[V52] capability_skills 列已添加；deepseek 已回填 %/2，其余内置已回填 %/9，内置模型总数 %', deepseek_cnt, other_cnt, total_cnt;
END $$;
