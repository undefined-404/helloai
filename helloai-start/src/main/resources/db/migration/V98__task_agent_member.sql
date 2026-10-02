-- V98__task_agent_member.sql
-- Task-Team 运行时成员表（持久化）：任务级授权边界的事实来源
-- 设计依据：doc/design/HelloAI Task-Team 与附件可见性设计.md §3
--
-- 为什么持久化：需应对分布式场景（网络中断 / 系统崩溃）——成员身份必须可恢复、可审计，
-- 并能支持"提前授权待命成员"（推导式做不到）。
--
-- 写入策略（关键）：事件驱动派生，**不做双写**。唯一权威源是 sub_task.assigned_agent_id 的
-- 变更入口（分配 / 认领 / 改派 / 死信指派），本表是其派生快照，幂等 upsert 写入；
-- 并可由 sub_task.assigned_agent_id ∪ agent_execution_record.agent_id 重建对账（启动一次性，幂等）自愈。
--
-- 语义：**加入过即成员** —— 改派不写 leave_time/LEFT（符合"改派进来的也能看"的诉求）。

CREATE TABLE IF NOT EXISTS task_agent_member (
    id          BIGINT      NOT NULL PRIMARY KEY,
    task_id     BIGINT      NOT NULL REFERENCES task(id),
    agent_id    BIGINT      NOT NULL REFERENCES agent(id),
    join_source VARCHAR(32) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    join_time   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    leave_time  TIMESTAMPTZ,
    create_by   VARCHAR(64) NOT NULL DEFAULT '',
    update_by   VARCHAR(64) NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    remark      VARCHAR(255),
    CONSTRAINT chk_task_agent_member_status CHECK (status IN ('ACTIVE', 'LEFT')),
    CONSTRAINT chk_task_agent_member_source
        CHECK (join_source IN ('ASSIGNED', 'CLAIMED', 'REASSIGNED', 'MANUAL', 'REBUILT'))
);

COMMENT ON TABLE task_agent_member IS 'Task-Team 运行时成员（任务级授权边界）：agent 在一个任务内参与过即入表，作为附件 visibility=TASK 范围的判据';
COMMENT ON COLUMN task_agent_member.join_source IS '入队来源：ASSIGNED=拆解分配 / CLAIMED=外部认领 / REASSIGNED=改派 / MANUAL=人工登记（待命成员）/ REBUILT=重建对账';
COMMENT ON COLUMN task_agent_member.status IS 'ACTIVE=在队（加入过即 ACTIVE，改派不置 LEFT）；LEFT 为将来"离队即失权"预留，当前不用';

-- (task_id, agent_id) 唯一：同一 agent 在一个任务内只有一条成员记录（幂等 upsert 的冲突目标）
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_agent_member
    ON task_agent_member(task_id, agent_id);

-- 判据热路径一：给定 (taskId, agentId) 判成员（canRead 主查）
CREATE INDEX IF NOT EXISTS idx_task_agent_member_task
    ON task_agent_member(task_id, status) WHERE deleted = 0;

-- 判据热路径二：给定 agent 反查其参与的任务
CREATE INDEX IF NOT EXISTS idx_task_agent_member_agent
    ON task_agent_member(agent_id, status) WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_task_agent_member_update_time ON task_agent_member;
CREATE TRIGGER update_task_agent_member_update_time
    BEFORE UPDATE ON task_agent_member
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();
