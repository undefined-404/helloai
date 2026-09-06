-- ============================================================
-- V70__create_team.sql
-- 用途：Team 组合（N-002，Phase 2 C2-S1）
-- 背景：
--   Team = "命名可复用的 Agent 组合 + 槽位填充参数源"，不是第二调度器/第二控制面
--   （C2 设计：组合声明与白名单展开；派发/执行/收敛完全复用现有调度链 AgentSelector）。
--   S1 只建 team + team_member 两张表；agent_policy.teamId 展开（S2）复用现有白名单链路。
-- 关键设计：
--   - team：命名组合元信息（status=DRAFT/ACTIVE/ARCHIVED，生命周期）
--   - team_member：槽位声明（agent 引用 + slot_role，agent 表零侵入）
--   - slot_role 白名单：PLANNER/EXECUTOR/REVIEWER（SYSTEM 仅系统事件，不做业务槽位）
--   - 唯一约束 (team_id, agent_id)：一个 agent 在 team 内单一槽位（平面组合，不做多角色）
--   - 成员删除用物理删除（避免逻辑删除行与唯一约束冲突导致无法重新加入）
-- 参考：doc/design/HelloAI_Phase2_C2_Team编排设计预研.md §3.1/§3.4
-- ============================================================

CREATE TABLE IF NOT EXISTS team (
    id          BIGINT       NOT NULL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(500),
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    create_by   VARCHAR(64)  NOT NULL DEFAULT '',
    update_by   VARCHAR(64)  NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    remark      VARCHAR(255),
    CONSTRAINT chk_team_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT uk_team_name UNIQUE (name)
);
CREATE INDEX IF NOT EXISTS idx_team_status
    ON team(status) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_team_update_time ON team;
CREATE TRIGGER update_team_update_time BEFORE UPDATE ON team
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

CREATE TABLE IF NOT EXISTS team_member (
    id          BIGINT      NOT NULL PRIMARY KEY,
    team_id     BIGINT      NOT NULL,
    agent_id    BIGINT      NOT NULL,
    slot_role   VARCHAR(16) NOT NULL,
    weight      INT         NOT NULL DEFAULT 100,
    create_by   VARCHAR(64) NOT NULL DEFAULT '',
    update_by   VARCHAR(64) NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    remark      VARCHAR(255),
    CONSTRAINT chk_team_member_slot_role CHECK (slot_role IN ('PLANNER', 'EXECUTOR', 'REVIEWER')),
    CONSTRAINT uk_team_member_team_agent UNIQUE (team_id, agent_id)
);
CREATE INDEX IF NOT EXISTS idx_team_member_team
    ON team_member(team_id, slot_role);
DROP TRIGGER IF EXISTS update_team_member_update_time ON team_member;
CREATE TRIGGER update_team_member_update_time BEFORE UPDATE ON team_member
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE team IS 'Team 组合（N-002，C2 设计：命名可复用的 Agent 组合，槽位填充参数源；非第二控制面）';
COMMENT ON COLUMN team.status IS 'Team 生命周期：DRAFT / ACTIVE / ARCHIVED';
COMMENT ON COLUMN team.name IS '命名组合（如 前端专项组；唯一）';
COMMENT ON TABLE team_member IS 'Team 成员槽位（agent 引用 + 角色声明；agent 表零侵入）';
COMMENT ON COLUMN team_member.slot_role IS '槽位角色：PLANNER / EXECUTOR / REVIEWER（SYSTEM 不做业务槽位）';
COMMENT ON COLUMN team_member.weight IS '组内优先级（默认 100，预留，S1 不参与派发排序）';
