-- ============================================================
-- V74__sub_task_add_required_skills_constraints.sql
-- 用途：Planner 能力感知（G-010）—— 子任务级技能指派 + 执行约束
-- 背景：
--   拆解侧能力感知批次：Planner 拆解时注入技能目录、按执行者画像
--   自适应调节 Plan 粒度，并允许 LLM 给子任务指派 requiredSkills
--   （Late Skill Binding，能力感知 ≠ 执行者绑定，选人仍走 AgentSelector）
--   与 COARSE 粒度下的 constraints（不许改的事）。
--   - required_skills：JSONB 字符串数组，子任务级技能标签；装箱时
--     与 task.required_skills 取并集（子任务级在前，去重保序）
--   - constraints：text，执行约束（COARSE 粒度必填，FINE/STANDARD 可空）
-- 机制：
--   - 默认空数组 '[]' / NULL：存量数据行为与现状完全一致（并集退化为纯 task 级）
--   - 暂不加索引（数据量小，仅在拆解 / 装箱 / 审查侧按行读取）
-- 参考：doc/design/Planner_Capability_Awareness.md
-- ============================================================

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS required_skills JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS constraints TEXT;

COMMENT ON COLUMN sub_task.required_skills IS '子任务级技能标签（JSONB 字符串数组；拆解侧指派，与任务级并集装箱，子任务级在前）';
COMMENT ON COLUMN sub_task.constraints IS '执行约束（不许改的事；COARSE 粒度必填，FINE/STANDARD 可空）';

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V74] sub_task.required_skills / constraints 列已就绪';
END $$;