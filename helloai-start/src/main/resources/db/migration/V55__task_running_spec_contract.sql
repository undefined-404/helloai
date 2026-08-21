-- ============================================================
-- V55: task_running_spec 增加 contract 列（Phase 2 契约先行拆解预留）
-- ------------------------------------------------------------
-- 背景：Phase 2 契约先行拆解模式。Planner 识别多模块接口/多组件协作任务时，
--   强制生成 1 个「契约定义」子任务；该子任务 DONE 后，契约产出回流写入
--   task_running_spec.contract，并由 buildExecutorPromptSection /
--   JsonbPromptRenderer 在 Baseline 之后渲染「## 任务契约」节，全局注入所有
--   下游子任务执行 Prompt。
-- 形态：JSONB（Map 结构，TaskRunningSpec.toMap/fromMap 往返）。
-- 本迁移只加列，Phase 2 双实现（Jsonb 侧读 task.context.runningSpec、
--   Table 侧 specMapper.updateContract）与渲染节落地。
-- ============================================================

ALTER TABLE task_running_spec
    ADD COLUMN IF NOT EXISTS contract JSONB;

COMMENT ON COLUMN task_running_spec.contract IS '任务契约（契约先行拆解模式）：契约定义子任务产出回流，全局注入下游执行 Prompt（Phase 2 启用）';

-- ============================================================
-- 验证
-- ============================================================

DO $$
DECLARE
    col_count INT;
BEGIN
    SELECT COUNT(*) INTO col_count
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'task_running_spec'
      AND column_name = 'contract';
    RAISE NOTICE '[V55] task_running_spec.contract 已就绪: col=%', col_count;
END $$;
