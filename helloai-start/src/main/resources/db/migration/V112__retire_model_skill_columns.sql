-- ============================================================
-- V112__retire_model_skill_columns.sql
-- 用途：`M-9`「模型能力 → Agent 技能」判据切换的**收尾** —— 把 `V52` 两列
--   `capability_skills` / `available_optional_skills` 从「白名单」退役，
--   能力判据统一落在 `V110` 的能力谓词列 `capabilities`(JSONB)。
-- ------------------------------------------------------------
-- 前置（本迁移执行前，消费方已切换；见 Java 侧同批改动）：
--   · `AgentSkillPolicyService.validateAgentSkills` / `AgentSkillDeriver.deriveWithCapabilities`
--     已改由 `capabilities` 谓词判定（`thinking←reasoning`、`web-search←webSearch`，
--     作业类型技能恒真）；
--   · `AdminLlmProviderController#getSkillOptions` 已改由谓词派生。
-- 本迁移是**不可逆的物理删列**：删前先把两列**归档**（快照表），并内置**回滚 SQL**（见文末），
--   以覆盖「切换后才发现口径偏差」的回退需求。
-- ------------------------------------------------------------
-- ★为什么可以无损删：`M-9` 已实测（离线核对，非本迁移职责）——
--   ① `available_optional_skills` 与 `capabilities->>'webSearch'` **严格双射**
--      （`(available_optional_skills ? 'web-search') == (capabilities->>'webSearch' = 'true')`，
--       mismatch = 0）；
--   ② `capability_skills` 的 distinct = 1（恒 `["thinking"]`，158/158）；
--   ③ 差集只有 `web-search` 一项 ⇒ 谓词可 100% 复现两列的「呈现」口径。
--   `thinking` 的驱动由「恒定列」改为「`reasoning` 谓词」（裁定 `A-升级`）——
--   48 行 `reasoning=false` 的模型其锁定技能由 `["thinking"]` 变 `[]`，属**有意**行为变更。
-- ------------------------------------------------------------
-- 依赖：`V110`（`capabilities` 列已存在并回填）、`V111`（当前 head）。
-- 注意：不修改任何已 apply 的 V*.sql（Flyway 校验和不可变）。
-- ============================================================

-- ============================================================
-- 1) 前置断言：库非空 + 双射成立 + `capability_skills` distinct=1 + `capabilities` 无缺字段
--    任一不成立即 EXCEPTION 中止（fail-closed，绝不带着未承接的数据删列）
-- ============================================================

DO $$
DECLARE
    total_cnt    INT;
    mismatch_cnt INT;
    distinct_cnt INT;
    missing_cnt  INT;
BEGIN
    -- ① 非空库（没有存活行说明前置迁移未生效，删列无意义且危险）
    SELECT COUNT(*) INTO total_cnt FROM llm_provider_model WHERE deleted = 0;
    IF total_cnt = 0 THEN
        RAISE EXCEPTION '[V112] llm_provider_model 无存活行（deleted=0）⇒ 前置迁移未生效，拒绝删列';
    END IF;

    -- ② 双射：available_optional_skills ? 'web-search'  ==  capabilities->>'webSearch' = 'true'
    SELECT COUNT(*) INTO mismatch_cnt FROM llm_provider_model
        WHERE deleted = 0
          AND ((available_optional_skills ? 'web-search')
               IS DISTINCT FROM (capabilities->>'webSearch' = 'true'));
    IF mismatch_cnt <> 0 THEN
        RAISE EXCEPTION '[V112] 双射失配 % 行（web-search ↔ webSearch）⇒ 谓词未完整承接，拒绝删列', mismatch_cnt;
    END IF;

    -- ③ capability_skills 的 distinct = 1（恒 ["thinking"]，与 reasoning 无关）
    SELECT COUNT(DISTINCT capability_skills) INTO distinct_cnt
        FROM llm_provider_model WHERE deleted = 0;
    IF distinct_cnt <> 1 THEN
        RAISE EXCEPTION '[V112] capability_skills distinct=%（期望 1）⇒ 口径已漂移，拒绝删列', distinct_cnt;
    END IF;

    -- ④ capabilities 无缺字段（input/output 为数组且非空；三个 bool 键均存在）
    SELECT COUNT(*) INTO missing_cnt FROM llm_provider_model
        WHERE deleted = 0 AND (capabilities IS NULL
              OR jsonb_typeof(capabilities->'input')  IS DISTINCT FROM 'array'
              OR jsonb_typeof(capabilities->'output') IS DISTINCT FROM 'array'
              OR capabilities->'input'  = '[]'::jsonb
              OR capabilities->'output' = '[]'::jsonb
              OR NOT (capabilities ? 'reasoning')
              OR NOT (capabilities ? 'toolCalling')
              OR NOT (capabilities ? 'webSearch'));
    IF missing_cnt <> 0 THEN
        RAISE EXCEPTION '[V112] capabilities 缺字段 % 行 ⇒ 谓词未完整承接，拒绝删列', missing_cnt;
    END IF;
END $$;

-- ============================================================
-- 2) 归档表：删列前的两列快照（供回滚 / 审计）
--    ★冲突键是**主键**（`id` PK），非部分唯一索引 ⇒ 归档 `ON CONFLICT` **不需要** WHERE deleted=0 谓词
-- ============================================================

CREATE TABLE IF NOT EXISTS llm_provider_model_skill_cols_archive (
    id                        BIGINT      PRIMARY KEY,
    provider_code             VARCHAR(64),
    model_name                VARCHAR(128),
    capability_skills         JSONB,
    available_optional_skills JSONB,
    archived_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE llm_provider_model_skill_cols_archive IS
    'V112 删列前归档：llm_provider_model 的 V52 两列（capability_skills / available_optional_skills）快照，供回滚 / 审计';
COMMENT ON COLUMN llm_provider_model_skill_cols_archive.id IS '原 llm_provider_model.id（主键，回滚按此 id 落回）';

-- ============================================================
-- 3) 归档：`INSERT … SELECT … ON CONFLICT (id) DO NOTHING`
-- ============================================================

INSERT INTO llm_provider_model_skill_cols_archive
    (id, provider_code, model_name, capability_skills, available_optional_skills)
SELECT id, provider_code, model_name, capability_skills, available_optional_skills
  FROM llm_provider_model
 WHERE deleted = 0
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- 4) 立即断言行数：`INSERT…SELECT` 无匹配行会**静默插 0 行**，必须显式拦
--    要求 arch > 0 且 arch >= src
-- ============================================================

DO $$
DECLARE
    src_cnt  INT;
    arch_cnt INT;
BEGIN
    SELECT COUNT(*) INTO src_cnt  FROM llm_provider_model WHERE deleted = 0;
    SELECT COUNT(*) INTO arch_cnt FROM llm_provider_model_skill_cols_archive;
    IF arch_cnt = 0 THEN
        RAISE EXCEPTION '[V112] 归档表 0 行 ⇒ INSERT…SELECT 静默插 0 行，拒绝继续删列';
    END IF;
    IF arch_cnt < src_cnt THEN
        RAISE EXCEPTION '[V112] 归档行数 % < 存活行数 % ⇒ 归档不完整，拒绝删列', arch_cnt, src_cnt;
    END IF;
    RAISE NOTICE '[V112] 已归档 V52 两列快照 % 行（存活行 %）', arch_cnt, src_cnt;
END $$;

-- ============================================================
-- 5) 物理删两列（`IF EXISTS` 幂等）
-- ============================================================

ALTER TABLE llm_provider_model DROP COLUMN IF EXISTS capability_skills;
ALTER TABLE llm_provider_model DROP COLUMN IF EXISTS available_optional_skills;

-- ============================================================
-- 6) 摘掉 `capabilities` 的列默认值（结构性 fail-fast 守卫）
--    摘后任何 INSERT 未显式写谓词即 NOT NULL 违约 ⇒ 后加模型不会静默丢谓词
-- ============================================================

ALTER TABLE llm_provider_model ALTER COLUMN capabilities DROP DEFAULT;

-- ============================================================
-- 7) 后置断言：两列在 information_schema 中为 0 行；`capabilities.column_default IS NULL`
-- ============================================================

DO $$
DECLARE
    col_cnt INT;
    def_cnt INT;
BEGIN
    SELECT COUNT(*) INTO col_cnt FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'llm_provider_model'
          AND column_name IN ('capability_skills', 'available_optional_skills');
    IF col_cnt <> 0 THEN
        RAISE EXCEPTION '[V112] 两列仍存在 % 列 ⇒ DROP COLUMN 未生效', col_cnt;
    END IF;

    SELECT COUNT(*) INTO def_cnt FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'llm_provider_model'
          AND column_name = 'capabilities' AND column_default IS NOT NULL;
    IF def_cnt <> 0 THEN
        RAISE EXCEPTION '[V112] capabilities 仍有列默认值 ⇒ DROP DEFAULT 未生效（fail-fast 守卫缺失）';
    END IF;
    RAISE NOTICE '[V112] 完成：V52 两列已退役，capabilities 默认值已摘除';
END $$;

COMMENT ON COLUMN llm_provider_model.capabilities IS
    '模型能力谓词（JSONB）：{"input":[模态],"output":[模态],"reasoning":bool,"toolCalling":bool,"webSearch":bool}。模态词表开放（未知 token 原样保留）；reasoning 对应前端「思考」开关（thinking 派生依据）；webSearch 为 provider 级工具可用性（web-search 派生依据）。形态乙（D-2026-10-13-9）；M-9 起为技能判定唯一依据，V52 两列已退役';

-- ============================================================
-- ★回滚 SQL（本项目无 flyway-maven-plugin；如需回退，手工执行以下语句）
-- ------------------------------------------------------------
-- ALTER TABLE llm_provider_model
--     ADD COLUMN IF NOT EXISTS capability_skills JSONB NOT NULL DEFAULT '["thinking"]'::jsonb;
-- ALTER TABLE llm_provider_model
--     ADD COLUMN IF NOT EXISTS available_optional_skills JSONB NOT NULL DEFAULT '["shell","code-review"]'::jsonb;
--
-- UPDATE llm_provider_model m
--    SET capability_skills         = a.capability_skills,
--        available_optional_skills = a.available_optional_skills
--   FROM llm_provider_model_skill_cols_archive a
--  WHERE m.id = a.id;
--
-- ALTER TABLE llm_provider_model
--     ALTER COLUMN capabilities SET DEFAULT
--         '{"input":["text"],"output":["text"],"reasoning":false,"toolCalling":false,"webSearch":false}';
--
-- （回滚仅需以上四步；Java 侧同步回退消费方切换即可。归档表保留不删，供再次核对。）
-- ============================================================
