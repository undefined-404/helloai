-- ============================================================
-- V72__task_priority.sql
-- 用途：任务优先级（N-006，Phase 2 C4-S1）
-- 背景：
--   当前调度是"即时选人"（PENDING+ready 立即派发，无排队层）；sub_task.priority 自 V1
--   存在但调度零消费，task 表无 priority 字段。N-006 首版 = 让优先级进入派发排序
--   （Task 继承 + 子任务按优先级出队近似）+ aging 防饿死（C4 设计 §3.1/§3.3）。
-- 关键设计：
--   - task.priority：HIGH / MEDIUM / LOW（默认 MEDIUM），与 sub_task.priority 值域对齐
--   - sub_task 创建时未显式指定 priority → 继承 task.priority（优先级继承，三入口统一）
-- 参考：doc/design/HelloAI_Phase2_C4_优先级调度设计预研.md §3.1/§3.5
-- ============================================================

ALTER TABLE task ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM';
CREATE INDEX IF NOT EXISTS idx_task_priority
    ON task(priority, status) WHERE deleted = 0;

COMMENT ON COLUMN task.priority IS '任务优先级：HIGH / MEDIUM / LOW（默认 MEDIUM；N-006：sub_task 创建未显式指定时继承，且调度按优先级出队）';
