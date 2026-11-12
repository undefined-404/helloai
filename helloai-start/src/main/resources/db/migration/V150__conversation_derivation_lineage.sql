-- ══════════════════════════════════════════════════════════════════════════
-- V150：派生会话的两列血缘（requirement_conversation）
--
--   source_draft_code = 草案期派生的父锚（草案尚未物化，锚为 (task_id, code)）
--   derived_task_id   = 本会话产出的独立任务 Y（普通会话为 NULL）
--
-- 本文件合并了原 V151__conversation_derived_task_id.sql。合并理由（2026-11-12
-- 用户裁定）：一次版本变更只出一个迁移号，同批次的结构调整全部并入当前未发布的
-- V150，不「改个字段就新开一个版本号」——否则迁移号迅速膨胀，升级路径也变长。
--
-- ── 背景一（source_draft_code，ADR-008 §5 草案期派生）──────────────────────
-- 草案审阅页的「拆出去」原先直接建派生任务 Y 并让 Y 立刻自拆，全程不建会话——于是
-- 「仓库在哪 / 运行时有什么 / 数据源是哪种」这类执行前提没人问，Y 拆出来的子任务
-- 只能写「仓库中」这类无指代表述，执行侧必然撞「缺仓库检出 / schema / DB 连接」
-- 而人工介入。改为与会话绑定后需要一条锚记录「这条会话是哪条草案拆出来的」：
--   · 执行期（子任务已物化）用 source_sub_task_id（V148 已有）
--   · 草案期（子任务尚未物化）用 (task_id, source_draft_code)
-- 二者互斥，任一非空即表示「本会话终稿建的是派生任务 Y 而不是普通任务」。
--
-- 为什么不复用 task.context / final_package：context 是执行上下文，final_package
-- 是终稿需求包，语义都被占；草案编号是**血缘**，且要在建任务之前被查询
-- （findActiveBySourceDraft 判幂等），塞进 JSONB 会让这次查询退化成全表扫。
--
-- ── 背景二（derived_task_id）──────────────────────────────────────────────
-- 2026-11-12 真机：草案「拆出去」→ 开会话 A → 终稿建派生任务 Y。A.task_id 按
-- ADR-008 §13.1 预挂父任务 X（会话挂在父任务下），于是：
--   · 会话里的「查看任务」按 task_id 跳转 ⇒ 跳到父任务 X 的方案审阅；
--   · 用户在 X 的方案审阅点「拒绝重拆」⇒ rejectPlan(X) 作废 X 整轮草案，并级联
--     取消派生任务 Y 及其全部子任务——用户以为在重拆 Y，实际重置了整条链；
--   · 同一个 X 名下因此挂着两条会话（X 自己的 + 派生的），「对哪个任务重拆」无从分辨。
--
-- task_id 的语义不能改：草案期定稿要拿它当父锚（assertDraftStillStaged /
-- createFromDraftAnchor 都要 (task_id, source_draft_code)）。故增设产出指针：
--   task_id         = 会话挂在哪个任务下（派生会话 = 父任务 X）
--   derived_task_id = 本会话派生出的独立任务 Y（普通会话为 NULL）
-- 读侧一律「derived_task_id 优先，回退 task_id」，语义统一为「这条会话产出了哪个任务」。
--
-- 为什么不反过来给父锚新开一列：那样要改 createFromDraftAnchor /
-- findActiveBySourceDraft / PlannerAgentPicker 三处既有读取，迁移面更大；
-- 而产出指针是纯新增，老行按血缘回填即可，回退行为与改造前一致。
--
-- ── 可重放性（生产增量更新要求）────────────────────────────────────────────
-- 全文幂等：ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS / 回填带
-- derived_task_id IS NULL 守卫。对已手工建过对象的库重放只会命中「跳过分支」，
-- 对停在 V149 的库则一次建齐。ADD COLUMN（可空、无 default）在 PG11+ 只改元数据，
-- 不重写表。
-- ══════════════════════════════════════════════════════════════════════════

-- ① 草案期派生的父锚
ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS source_draft_code VARCHAR(64);

COMMENT ON COLUMN requirement_conversation.source_draft_code IS
    '草案期重构派生的来源草案编号（ADR-008 §5）：与 task_id 组成父锚，非空表示终稿要建派生任务 Y；普通会话为 NULL';

-- ② 派生会话的产出指针
ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS derived_task_id BIGINT;

COMMENT ON COLUMN requirement_conversation.derived_task_id IS
    '派生会话产出的独立任务 Y（ADR-008 §5/§13，软引用无 FK）：非空表示本会话终稿建的是派生任务；此时 task_id 仍是父任务 X';

-- ③ 存量回填：两个锚互斥，各自只匹配「未取消」的派生行
--    （uk_task_parent_draft_active / uk_task_parent_sub_task_active 保证至多一条）
UPDATE requirement_conversation c
   SET derived_task_id = t.id
  FROM task t
 WHERE c.derived_task_id IS NULL
   AND c.deleted = 0
   AND c.source_draft_code IS NOT NULL
   AND t.deleted = 0
   AND t.parent_task_id = c.task_id
   AND t.parent_draft_code = c.source_draft_code
   AND t.status <> 'CANCELLED';

UPDATE requirement_conversation c
   SET derived_task_id = t.id
  FROM task t
 WHERE c.derived_task_id IS NULL
   AND c.deleted = 0
   AND c.source_sub_task_id IS NOT NULL
   AND t.deleted = 0
   AND t.parent_sub_task_id = c.source_sub_task_id
   AND t.status <> 'CANCELLED';

-- ④ 两列各一只部分索引（血缘查询都带 deleted = 0 且列非空）
CREATE INDEX IF NOT EXISTS idx_req_conv_source_draft
    ON requirement_conversation (task_id, source_draft_code)
    WHERE source_draft_code IS NOT NULL AND deleted = 0;

CREATE INDEX IF NOT EXISTS idx_req_conv_derived_task
    ON requirement_conversation (derived_task_id)
    WHERE derived_task_id IS NOT NULL AND deleted = 0;

-- ⑤ 落地断言：任一列缺失就让迁移失败，别等应用起来写会话才 500
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.columns
                   WHERE table_name = 'requirement_conversation'
                     AND column_name = 'source_draft_code') THEN
        RAISE EXCEPTION 'V150 未生效：requirement_conversation.source_draft_code 不存在';
    END IF;
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.columns
                   WHERE table_name = 'requirement_conversation'
                     AND column_name = 'derived_task_id') THEN
        RAISE EXCEPTION 'V150 未生效：requirement_conversation.derived_task_id 不存在';
    END IF;
END
$$;
