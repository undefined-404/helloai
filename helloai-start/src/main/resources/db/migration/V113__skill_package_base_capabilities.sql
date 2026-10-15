-- ============================================================
-- V113__skill_package_base_capabilities.sql
-- 用途：`G-022` 修复第1 步 —— 为已安装技能包补「细粒度技能 → 粗粒度模型能力」的
--   **引用边**列 `required_base_capabilities`(JSONB)，与内置源 frontmatter
--   新增的 `requiredBaseCapabilities` 键**同名字段**。
-- ------------------------------------------------------------
-- 问题（实测，本地 PG）：
--   · Planner 拆解产出的 `sub_task.required_skills` **全部**是 `eng-*`
--     （34 行实测：eng-doc-standard / eng-verification / eng-web-research /
--      eng-code-review，无一条标准能力标签）；
--   · 而 `agent.skills` 是**模型能力标签**（thinking / code-review / web-search /
--     shell …，由 `SkillCapabilityRequirements` 从 `llm_provider_model.capabilities`
--     谓词派生）；
--   · 选人处 `AgentSelectionConstraints.denialReason` 走
--     `SkillNormalizer.matches`（同义词表只覆盖 8 项标准词，`eng-*` **零映射**）
--     ⇒ AND 匹配恒失败 ⇒ `skill_not_matched` ⇒ 子任务永久 PENDING。
--   两个命名空间之间**没有任何引用边**，这是「有需求、无人被选上」的结构性根因。
-- ------------------------------------------------------------
-- ★为什么本列`NOT NULL DEFAULT '[]'`（与 `M-9` 摘 `capabilities` 默认值相反）：
--   本迁移**新增**一列且**存量已安装包全部无该语义**（装入时该 frontmatter 键尚不存在），
--   空集默认值在此处**是正确值**而非「静默丢数据」——存量包的引用边本就是空集。
--   真正的 fail-fast 价值在**写入侧**：`SkillPackageInstallService` 与本迁移同批发布，
--   显式写 frontmatter 解析值 ⇒ 此后新装包不会漏写。
--   与 `M-9` 的差别在于：那里是「值已有、默认值会让后加行静默丢谓词」，
--   这里是「值本就为空、默认值即事实」——**同一手法不可脱离场景照搬**。
-- ------------------------------------------------------------
-- 依赖：`V104`（`skill_package` 表已存在）、`V112`（当前 head）。
-- 注意：不修改任何已 apply 的 V*.sql（Flyway 校验和不可变）。
-- ============================================================

ALTER TABLE skill_package
    ADD COLUMN IF NOT EXISTS required_base_capabilities JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN skill_package.required_base_capabilities IS
    '技能包所需的模型能力谓词（JSONB 数组，元素取 skillCapabilityRequirements.provides 词表：reasoning / webSearch / toolCalling）。判定式：技能可派 ⟺ requires ⊆ 模型 provides（G-022 引用边）；与 required_tools 正交——后者是工具依赖（喂 ToolRegistry），本列是模型能力依赖（喂 llm_provider_model.capabilities）';

-- ============================================================
-- ★回滚 SQL（本项目无 flyway-maven-plugin；如需回退，手工执行以下语句）
-- ------------------------------------------------------------
-- ALTER TABLE skill_package DROP COLUMN IF EXISTS required_base_capabilities;
--
-- （纯加列，回滚不丢数据：Java 侧同步移除 `SkillPackage.requiredBaseCapabilities`
--   与 frontmatter 的 `requiredBaseCapabilities` 键即可。）
-- ============================================================