-- ============================================================
-- V69__create_workflow_instance.sql
-- 用途：Workflow 实例（N-001，Phase 2 C1-S2）
-- 背景：
--   实例化 = 模板（当前激活版本） + 参数 → 一次性物化 task/sub_task（C1 设计 D1），
--   实例表只做"绑定快照"：绑定 version_id + params + 物化出的 task_id。
-- 关键设计（C1 设计 D6，用户审阅拍板）：
--   - 实例状态【纯查询聚合】：不落权威 status 列（节点完成度每次从 sub_task 现算），
--     仅保留可选 status_snapshot（展示快照，无状态机、无约束力）
--   - 反锁禁令：实例状态是派生态投影，绝不反向约束 task/sub_task 操作——
--     SQL 层不设任何引用约束/级联，实例删除/状态变更不影响已物化任务
-- 参考：doc/design/HelloAI_Phase2_C1_Workflow模板设计预研.md §3.3/§4.2
-- ============================================================

CREATE TABLE IF NOT EXISTS workflow_instance (
    id              BIGINT       NOT NULL PRIMARY KEY,
    template_id     BIGINT       NOT NULL,
    version_id      BIGINT       NOT NULL,
    params          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    task_id         BIGINT       NOT NULL,
    status_snapshot VARCHAR(16),
    start_time      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    end_time        TIMESTAMPTZ,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_workflow_instance_template
    ON workflow_instance(template_id, version_id);
CREATE INDEX IF NOT EXISTS idx_workflow_instance_task
    ON workflow_instance(task_id);
DROP TRIGGER IF EXISTS update_workflow_instance_update_time ON workflow_instance;
CREATE TRIGGER update_workflow_instance_update_time BEFORE UPDATE ON workflow_instance
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE workflow_instance IS 'Workflow 实例（C1 设计：绑定版本快照 + params + 物化 task_id；实例为一次性物化产物，运行期由现有调度链接管）';
COMMENT ON COLUMN workflow_instance.params IS '实例化参数（含占位符渲染输入，如 goal/business_area）';
COMMENT ON COLUMN workflow_instance.task_id IS '物化出的主任务（实例 ↔ Task 1:1）';
COMMENT ON COLUMN workflow_instance.status_snapshot IS '展示快照（无状态机、无约束力；权威状态从 sub_task 纯查询聚合，D6-2）';
COMMENT ON COLUMN workflow_instance.start_time IS '实例化时间';
COMMENT ON COLUMN workflow_instance.end_time IS '结束时间（可空，运行期不写——实例为派生态投影，不反锁任务）';
