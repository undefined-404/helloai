-- ============================================================
-- V56: sub_task 增加 is_contract 列（Phase 2 契约先行拆解预留）
-- ------------------------------------------------------------
-- 背景：Phase 2 契约先行拆解模式。planner-decompose.md 规则要求多模块接口 /
--   多组件协作任务强制生成 1 个「契约定义」子任务（JSON 可选字段
--   "contract": true），PlannerAnalysisServiceImpl 解析后落库本列；
--   该子任务 DONE 后契约产出回流 task_running_spec.contract（V55）。
-- 形态：SMALLINT NOT NULL DEFAULT 0（规范 §9.3：布尔统一用 smallint，
--   与 deleted / is_enabled 等既有列一致）。
-- 本迁移只加列，Phase 2 解析 / 回流 / 渲染落地。
-- ============================================================

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS is_contract SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sub_task.is_contract IS '契约定义子任务标记：0-普通子任务，1-契约定义子任务（产出回流 task_running_spec.contract，Phase 2 启用）';

-- ============================================================
-- 验证
-- ============================================================

DO $$
DECLARE
    col_count INT;
BEGIN
    SELECT COUNT(*) INTO col_count
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'sub_task'
      AND column_name = 'is_contract';
    RAISE NOTICE '[V56] sub_task.is_contract 已就绪: col=%', col_count;
END $$;
