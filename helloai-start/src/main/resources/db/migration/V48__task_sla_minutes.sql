-- A0-7（§6.66）：任务级 SLA 分钟数（可空，null=无时限）
-- 子任务 deadline 在计划确认（confirmPlan）时按 确认时刻 + sla_minutes 下发
ALTER TABLE task ADD COLUMN sla_minutes INT;

COMMENT ON COLUMN task.sla_minutes IS '任务 SLA 分钟数（A0-7）：可空，null=无时限；confirmPlan 时按 now+sla_minutes 下发子任务 deadline';
