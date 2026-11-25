-- V154__task_constraint_refs.sql
-- G-037 任务约束类型化试点：子任务 / 拆解草案的约束从「单值裁决文本」升级为
-- 「裁决文本 + 契约文档引用」并存的类型化列表约束。
--
-- constraint_refs JSONB = [{ kind, ref, scope[] }]
--   kind  ∈ TEXT（内联裁决，试点不落本列）/ DOCUMENT（契约文档引用）/ SKILL（技能，预留）
--   ref   = 约束文档目录注册名（constraint-docs/manifest.json 白名单，防任意文件读取）
--   scope = 章节 id 子集（空 = 整文档）
-- 与既有 constraints TEXT（G-010，COARSE 裁决）并存互补；空数组 = 无契约引用，存量零影响。

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS constraint_refs JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE task_plan_draft
    ADD COLUMN IF NOT EXISTS constraint_refs JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN sub_task.constraint_refs
    IS '类型化约束引用列表 JSONB：[{kind,ref,scope[]}]，ref 必须命中 constraint-docs/manifest.json 白名单；正文经 internal_load_constraint_doc 按需读取（G-037）';
COMMENT ON COLUMN task_plan_draft.constraint_refs
    IS '类型化约束引用列表 JSONB（草案暂存，确认转正同步 sub_task.constraint_refs）(G-037)';