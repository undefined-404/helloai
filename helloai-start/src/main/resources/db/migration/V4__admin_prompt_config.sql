-- ============================================================
-- V4__admin_prompt_config.sql
-- 系统配置表 + 提示词模板表 + request_log 补充字段
-- ============================================================

-- 1. sys_config 系统配置表
CREATE TABLE IF NOT EXISTS sys_config (
    id              BIGINT NOT NULL PRIMARY KEY,
    config_key      VARCHAR(128) NOT NULL,
    config_value    TEXT NOT NULL,
    is_encrypted    SMALLINT NOT NULL DEFAULT 0,
    description     VARCHAR(500) NOT NULL DEFAULT '',
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_config_key ON sys_config(config_key) WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_sys_config_update_time ON sys_config;
CREATE TRIGGER update_sys_config_update_time BEFORE UPDATE ON sys_config
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sys_config IS '系统配置表';
COMMENT ON COLUMN sys_config.id IS '主键ID';
COMMENT ON COLUMN sys_config.config_key IS '配置键';
COMMENT ON COLUMN sys_config.config_value IS '配置值';
COMMENT ON COLUMN sys_config.is_encrypted IS '是否加密：0-否，1-是';
COMMENT ON COLUMN sys_config.description IS '配置说明';

-- 预置系统配置
INSERT INTO sys_config (id, config_key, config_value, description)
VALUES
    (1000000000000000001, 'system.name', 'HelloAI', '系统名称'),
    (1000000000000000002, 'system.description', 'AI Agent 协作调度平台', '系统描述'),
    (1000000000000000003, 'system.setup_finished', '0', '是否完成初始化向导：0-未完成，1-已完成')
ON CONFLICT (id) DO NOTHING;

-- 2. prompt_template 提示词模板表
CREATE TABLE IF NOT EXISTS prompt_template (
    id              BIGINT NOT NULL PRIMARY KEY,
    role            VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL DEFAULT '',
    content         TEXT NOT NULL,
    is_default      SMALLINT NOT NULL DEFAULT 0,
    version         INT NOT NULL DEFAULT 1,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_prompt_template_role ON prompt_template(role, deleted) WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_prompt_template_update_time ON prompt_template;
CREATE TRIGGER update_prompt_template_update_time BEFORE UPDATE ON prompt_template
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE prompt_template IS '提示词模板表';
COMMENT ON COLUMN prompt_template.id IS '主键ID';
COMMENT ON COLUMN prompt_template.role IS '角色：PLANNER/EXECUTOR/REVIEWER/PATROL';
COMMENT ON COLUMN prompt_template.name IS '模板名称';
COMMENT ON COLUMN prompt_template.content IS '提示词内容';
COMMENT ON COLUMN prompt_template.is_default IS '是否默认模板：0-否，1-是';
COMMENT ON COLUMN prompt_template.version IS '版本号';

-- 预置默认提示词模板
INSERT INTO prompt_template (id, role, name, content, is_default, remark)
VALUES
    (2000000000000000001, 'PLANNER', '规划者默认模板', '你是一个任务规划 Agent。请分析任务需求，将其拆解为可执行的模块和子任务。', 1, '规划者角色默认提示词'),
    (2000000000000000002, 'EXECUTOR', '执行者默认模板', '你是一个任务执行 Agent。请根据分配的子任务需求，输出完整的交付物。', 1, '执行者角色默认提示词'),
    (2000000000000000003, 'REVIEWER', '审查者默认模板', '你是一个质量审查 Agent。请审查执行者的交付物质量，给出评分和改进意见。', 1, '审查者角色默认提示词'),
    (2000000000000000004, 'PATROL', '巡检者默认模板', '你是一个巡检 Agent。请定期检查任务进展和 Agent 状态，及时发现异常。', 1, '巡检者角色默认提示词')
ON CONFLICT (id) DO NOTHING;

-- 3. request_log 补充字段
ALTER TABLE request_log
    ADD COLUMN IF NOT EXISTS status_code INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS auth_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS auth_id BIGINT;

COMMENT ON COLUMN request_log.status_code IS 'HTTP 状态码';
COMMENT ON COLUMN request_log.auth_type IS '认证类型：admin/agent';
COMMENT ON COLUMN request_log.auth_id IS '认证用户ID';

-- 4. activity_log 补充字段
ALTER TABLE activity_log
    ADD COLUMN IF NOT EXISTS level VARCHAR(16) NOT NULL DEFAULT 'INFO',
    ADD COLUMN IF NOT EXISTS source VARCHAR(64);

COMMENT ON COLUMN activity_log.level IS '日志级别：INFO/WARN/ERROR';
COMMENT ON COLUMN activity_log.source IS '来源：system/agent/admin';

-- 5. sys_user 补充字段
ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS email VARCHAR(128),
    ADD COLUMN IF NOT EXISTS phone VARCHAR(32);

COMMENT ON COLUMN sys_user.email IS '邮箱';
COMMENT ON COLUMN sys_user.phone IS '手机号';
