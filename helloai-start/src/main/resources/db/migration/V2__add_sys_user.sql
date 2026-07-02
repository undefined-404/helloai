-- ============================================================
-- V2__add_sys_user.sql
-- 系统用户表 + agent.api_key 索引
-- ============================================================

-- 1. sys_user 系统用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT NOT NULL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    password        VARCHAR(255) NOT NULL,
    nickname        VARCHAR(128) NOT NULL DEFAULT '',
    role            VARCHAR(32)  NOT NULL DEFAULT 'ADMIN',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_login_time TIMESTAMPTZ,
    last_login_ip   VARCHAR(64),
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_user_username ON sys_user(username) WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_sys_user_update_time ON sys_user;
CREATE TRIGGER update_sys_user_update_time BEFORE UPDATE ON sys_user
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sys_user IS '系统用户表（管理员）';
COMMENT ON COLUMN sys_user.id IS '主键ID';
COMMENT ON COLUMN sys_user.username IS '用户名';
COMMENT ON COLUMN sys_user.password IS '密码（BCrypt 加密）';
COMMENT ON COLUMN sys_user.nickname IS '昵称';
COMMENT ON COLUMN sys_user.role IS '角色：ADMIN / SUPER_ADMIN';
COMMENT ON COLUMN sys_user.status IS '状态：ACTIVE / DISABLED';
COMMENT ON COLUMN sys_user.last_login_time IS '最后登录时间';
COMMENT ON COLUMN sys_user.last_login_ip IS '最后登录 IP';

-- 2. agent.api_key 索引（已有 V1 建表，仅补充索引）
CREATE INDEX IF NOT EXISTS idx_agent_api_key ON agent(api_key) WHERE deleted = 0;

-- 删除旧的 V2 表（如果之前 Flyway 跑过 V2__add_admin_user）
DROP TABLE IF EXISTS admin_user CASCADE;
