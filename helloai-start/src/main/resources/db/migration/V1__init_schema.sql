-- ============================================================
-- HelloAI V1: 核心业务表（10张）
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- PG 触发器函数（替代 MySQL ON UPDATE CURRENT_TIMESTAMP）
CREATE OR REPLACE FUNCTION update_update_time_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_time = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- 1. task
CREATE TABLE IF NOT EXISTS task (
    id              BIGINT NOT NULL PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_task_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'CANCELLED'))
);
CREATE INDEX idx_task_status ON task(status, deleted) WHERE deleted = 0;
CREATE TRIGGER update_task_update_time BEFORE UPDATE ON task
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

-- 2. module
CREATE TABLE IF NOT EXISTS module (
    id              BIGINT NOT NULL PRIMARY KEY,
    task_id         BIGINT       NOT NULL REFERENCES task(id),
    name            VARCHAR(255) NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX idx_module_task ON module(task_id, deleted) WHERE deleted = 0;
CREATE TRIGGER update_module_update_time BEFORE UPDATE ON module
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

-- 3. sub_task（核心表）
CREATE TABLE IF NOT EXISTS sub_task (
    id              BIGINT NOT NULL PRIMARY KEY,
    task_id         BIGINT       NOT NULL REFERENCES task(id),
    module_id       BIGINT       REFERENCES module(id),
    title           VARCHAR(255) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    assigned_agent  BIGINT,
    content         TEXT,
    context         JSONB,
    score_factors   JSONB,
    composite_score INT,
    score_grade     VARCHAR(4),
    deadline        TIMESTAMPTZ,
    version         INT          NOT NULL DEFAULT 0,
    timeout_count   INT          NOT NULL DEFAULT 0,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_sub_task_status CHECK (
        status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS', 'REVIEW', 'DONE', 'REWORK', 'BLOCKED', 'CANCELLED')
    )
);
CREATE INDEX idx_sub_task_status ON sub_task(status, deleted) WHERE deleted = 0;
CREATE INDEX idx_sub_task_agent ON sub_task(assigned_agent, status) WHERE deleted = 0;
CREATE INDEX idx_sub_task_deadline ON sub_task(deadline, status) WHERE status IN ('IN_PROGRESS', 'ASSIGNED');
CREATE INDEX idx_sub_task_score ON sub_task((score_factors->>'grade')) WHERE score_factors IS NOT NULL;
CREATE TRIGGER update_sub_task_update_time BEFORE UPDATE ON sub_task
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

-- 4. agent
CREATE TABLE IF NOT EXISTS agent (
    id              BIGINT NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    api_key         VARCHAR(255),
    model_type      VARCHAR(64),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    score           INT          NOT NULL DEFAULT 0,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_agent_role CHECK (role IN ('PLANNER', 'EXECUTOR', 'REVIEWER', 'PATROL'))
);
CREATE INDEX idx_agent_role ON agent(role, status) WHERE deleted = 0;
CREATE TRIGGER update_agent_update_time BEFORE UPDATE ON agent
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

-- 5. review_record
CREATE TABLE IF NOT EXISTS review_record (
    id              BIGINT NOT NULL PRIMARY KEY,
    sub_task_id     BIGINT       NOT NULL REFERENCES sub_task(id),
    reviewer_agent  BIGINT       NOT NULL,
    result          VARCHAR(32)  NOT NULL,
    score           INT          NOT NULL DEFAULT 0,
    issues          TEXT,
    comment         TEXT,
    round           INT          NOT NULL DEFAULT 1,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT chk_review_result CHECK (result IN ('approved', 'rejected'))
);
CREATE INDEX idx_review_sub_task ON review_record(sub_task_id, round);
CREATE TRIGGER update_review_update_time BEFORE UPDATE ON review_record
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

-- 6. reward_log
CREATE TABLE IF NOT EXISTS reward_log (
    id              BIGINT NOT NULL PRIMARY KEY,
    agent_id        BIGINT       NOT NULL,
    sub_task_id     BIGINT,
    reason          VARCHAR(255) NOT NULL,
    delta           INT          NOT NULL,
    balance         INT          NOT NULL,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_reward_agent ON reward_log(agent_id, create_time);

-- 7. activity_log
CREATE TABLE IF NOT EXISTS activity_log (
    id              BIGINT NOT NULL PRIMARY KEY,
    agent_id        BIGINT,
    sub_task_id     BIGINT,
    action          VARCHAR(64)  NOT NULL,
    detail          JSONB,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_activity_agent ON activity_log(agent_id, create_time);
CREATE INDEX idx_activity_sub_task ON activity_log(sub_task_id);

-- 8. patrol_record
CREATE TABLE IF NOT EXISTS patrol_record (
    id              BIGINT NOT NULL PRIMARY KEY,
    sub_task_id     BIGINT       NOT NULL REFERENCES sub_task(id),
    patrol_agent    BIGINT       NOT NULL,
    alert_type      VARCHAR(64)  NOT NULL,
    description     TEXT,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_patrol_sub_task ON patrol_record(sub_task_id);

-- 9. request_log
CREATE TABLE IF NOT EXISTS request_log (
    id              BIGINT NOT NULL PRIMARY KEY,
    request_id      VARCHAR(64)  NOT NULL,
    method          VARCHAR(16)  NOT NULL,
    path            VARCHAR(255) NOT NULL,
    params          JSONB,
    response        JSONB,
    duration        INT,
    ip              VARCHAR(64),
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_request_time ON request_log(create_time);
CREATE INDEX idx_request_id ON request_log(request_id);

-- 10. rule
CREATE TABLE IF NOT EXISTS rule (
    id              BIGINT NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    rule_type       VARCHAR(32)  NOT NULL,
    priority        INT          NOT NULL DEFAULT 0,
    content         TEXT         NOT NULL,
    created_by      VARCHAR(64)  NOT NULL DEFAULT '',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_rule_type CHECK (rule_type IN ('global', 'module', 'agent'))
);
CREATE INDEX idx_rule_type ON rule(rule_type, priority) WHERE deleted = 0;
CREATE TRIGGER update_rule_update_time BEFORE UPDATE ON rule
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();
