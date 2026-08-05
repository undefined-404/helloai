-- ============================================================
-- V42__task_iteration.sql
-- 用途：任务级执行迭代表 —— 记录每一轮任务执行的完整快照
-- 背景：
--   现有 task / sub_task 只存当前态，task_timeline 面向事件审计，
--   task_execution_record 只存最终产出。缺少一张表完整记录：
--     - 本轮 LLM 请求了什么、返回了什么
--     - 审核是通过还是驳回
--     - 历史累计驳回了哪些意见
--     - 前置任务结果如何传递给本轮
--   本表填补这个空白，形成从需求到执行到审核的迭代全景快照。
-- 层级容纳：
--   parent_task_id 自引用，支持"超大类任务 → 按模块拆主任务 → 主任务再拆子任务"。
--   task_code 用于前端时序图展示（如 #1, #2），直观表达执行顺序。
-- 机制：
--   - 同一顶层 task 可有多条记录（rework 时 round_num 递增）
--   - rejection_history JSONB 累积全部历史驳回意见
--   - depends_on JSONB 存前置迭代记录 ID 数组
-- ============================================================

CREATE TABLE IF NOT EXISTS task_iteration (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    task_id             BIGINT       NOT NULL,
    task_code           VARCHAR(32)  NOT NULL DEFAULT '',
    task_name           VARCHAR(255) NOT NULL,
    task_type           VARCHAR(32)  NOT NULL DEFAULT 'DEVELOPMENT',
    parent_task_id      BIGINT,
    depends_on          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    round_num           INT          NOT NULL DEFAULT 1,
    prev_task_result    TEXT,
    current_requirement TEXT,
    last_result         TEXT,
    rejection_history   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    llm_response        TEXT,
    review_result       VARCHAR(16),
    executor_agent      VARCHAR(128),
    create_by           VARCHAR(64)  NOT NULL DEFAULT '',
    update_by           VARCHAR(64)  NOT NULL DEFAULT '',
    create_time         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0,
    remark              VARCHAR(500),
    CONSTRAINT chk_task_iteration_type CHECK (task_type IN ('DEVELOPMENT', 'TESTING', 'PLANNING', 'OTHER')),
    CONSTRAINT chk_task_iteration_review CHECK (review_result IS NULL OR review_result IN ('PASSED', 'REJECTED'))
);

-- 索引：按顶层任务拉取所有迭代记录
CREATE INDEX IF NOT EXISTS idx_task_iteration_task
    ON task_iteration(task_id, task_code) WHERE deleted = 0;
-- 索引：按父级拉取子树
CREATE INDEX IF NOT EXISTS idx_task_iteration_parent
    ON task_iteration(parent_task_id) WHERE deleted = 0;
-- 索引：按执行 Agent 拉取
CREATE INDEX IF NOT EXISTS idx_task_iteration_agent
    ON task_iteration(executor_agent) WHERE deleted = 0 AND executor_agent IS NOT NULL;
-- 索引：按审核结果筛选
CREATE INDEX IF NOT EXISTS idx_task_iteration_review
    ON task_iteration(review_result) WHERE deleted = 0 AND review_result IS NOT NULL;
-- 索引：按创建时间倒序
CREATE INDEX IF NOT EXISTS idx_task_iteration_time
    ON task_iteration(create_time DESC) WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_task_iteration_update_time ON task_iteration;
CREATE TRIGGER update_task_iteration_update_time BEFORE UPDATE ON task_iteration
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE  task_iteration IS '任务级执行迭代记录表 —— 每一轮执行（含 rework）一条记录，完整快照链';
COMMENT ON COLUMN task_iteration.id                  IS '主键ID (Snowflake)';
COMMENT ON COLUMN task_iteration.task_id             IS '关联顶层 task.id';
COMMENT ON COLUMN task_iteration.task_code           IS '任务序号（前端时序图展示，如 #1, #2）';
COMMENT ON COLUMN task_iteration.task_name           IS '任务名称（冗余，方便查询展示）';
COMMENT ON COLUMN task_iteration.task_type           IS '任务类型：DEVELOPMENT/TESTING/PLANNING/OTHER';
COMMENT ON COLUMN task_iteration.parent_task_id      IS '上级迭代记录 ID（自引用，NULL=顶级任务）';
COMMENT ON COLUMN task_iteration.depends_on          IS '前置依赖的迭代记录 ID 数组（JSONB）';
COMMENT ON COLUMN task_iteration.round_num           IS '迭代轮次（同一 task 的 rework 递增）';
COMMENT ON COLUMN task_iteration.prev_task_result    IS '前置任务结果汇总';
COMMENT ON COLUMN task_iteration.current_requirement IS '本轮任务要求';
COMMENT ON COLUMN task_iteration.last_result         IS '上次生成结果（rework 时携带）';
COMMENT ON COLUMN task_iteration.rejection_history   IS '全部历史驳回意见 [{round,comment,issues}]';
COMMENT ON COLUMN task_iteration.llm_response        IS '本轮 LLM 返回完整结果';
COMMENT ON COLUMN task_iteration.review_result       IS '审核结果：PASSED/REJECTED（NULL=未审核）';
COMMENT ON COLUMN task_iteration.executor_agent      IS '执行 Agent 模型注册名称';
COMMENT ON COLUMN task_iteration.create_by           IS '创建人';
COMMENT ON COLUMN task_iteration.update_by           IS '更新人';
COMMENT ON COLUMN task_iteration.create_time         IS '创建时间';
COMMENT ON COLUMN task_iteration.update_time         IS '更新时间';
COMMENT ON COLUMN task_iteration.deleted             IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN task_iteration.remark              IS '备注';

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V42] task_iteration 表已就绪';
END $$;
