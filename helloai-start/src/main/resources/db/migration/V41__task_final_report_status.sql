-- V41: task 增加最终整合报告生成状态列
-- 报告生成可能耗时数十秒到数分钟（Planner LLM 调用），此前无中间态：
-- 前端无法禁用重复生成按钮、手动+自动两条路径可并发触发 last-write-wins 覆盖。
-- 新增 final_report_status 表达 NONE/GENERATING/DONE/FAILED（见 FinalReportStatus 枚举），
-- 主任务状态 TaskStatus 保持 DONE 语义不变，两者解耦。

ALTER TABLE task
    ADD COLUMN IF NOT EXISTS final_report_status VARCHAR(16) NOT NULL DEFAULT 'NONE';

-- 回填：已有报告的存量任务视为 DONE
UPDATE task SET final_report_status = 'DONE'
WHERE final_report_status = 'NONE'
  AND final_report IS NOT NULL
  AND final_report <> '';

COMMENT ON COLUMN task.final_report_status IS '最终整合报告生成状态：NONE/GENERATING/DONE/FAILED（与任务主状态解耦）';
