-- V95: task 增加最终报告上一版槽（单槽列回滚，§12.1）
-- 每次覆盖生成（手动重新生成/审查返工）前，把被覆盖的那一版整体（正文/生成 Agent/时间）
-- 落入 prev 槽；回滚端点将 current 与 prev 整体互换，可反复切换。
-- 纯增值列：老数据三列全为 NULL（无上一版），无需数据迁移；无 CHECK/默认值。
-- prev_agent_id 软引用无 FK（与 final_report_agent_id 同款约定）。

ALTER TABLE task
    ADD COLUMN IF NOT EXISTS final_report_prev TEXT,
    ADD COLUMN IF NOT EXISTS final_report_prev_agent_id BIGINT,
    ADD COLUMN IF NOT EXISTS final_report_prev_time TIMESTAMPTZ;

COMMENT ON COLUMN task.final_report_prev IS '上一版最终整合报告正文（单槽列；回滚时与 current 互换；NULL=无上一版）';
COMMENT ON COLUMN task.final_report_prev_agent_id IS '上一版报告的生成 Planner Agent ID（软引用无 FK）';
COMMENT ON COLUMN task.final_report_prev_time IS '上一版报告生成时间';