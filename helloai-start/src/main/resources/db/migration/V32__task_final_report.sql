-- V32: task 增加最终整合报告三列
-- 任务收口后由 Planner Agent 把全部 DONE 子任务产出整合为一份最终报告（Markdown 长文本）。
-- 用专列 TEXT 而非 JSONB context：避免 Task 写入链引入 JacksonTypeHandler + XML 覆盖改造，
-- 报告本身就是单段长文本，专列语义更直接。agent_id 软引用无 FK（与 planner_agent_id 同款约定）。

ALTER TABLE task
    ADD COLUMN IF NOT EXISTS final_report TEXT,
    ADD COLUMN IF NOT EXISTS final_report_agent_id BIGINT,
    ADD COLUMN IF NOT EXISTS final_report_time TIMESTAMPTZ;

COMMENT ON COLUMN task.final_report IS '最终整合报告正文（Markdown；NULL=尚未生成）';
COMMENT ON COLUMN task.final_report_agent_id IS '生成报告的 Planner Agent ID（软引用无 FK）';
COMMENT ON COLUMN task.final_report_time IS '报告生成时间';
