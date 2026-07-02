-- ============================================================
-- HelloAI 开发期总初始化脚本
-- 说明：
-- 1. 基于原 V1 ~ V6 迁移折叠为单文件初始化
-- 2. 结构以当前实体类最终字段为准
-- 3. 统一补齐表注释、字段注释、基础审计字段和约束
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- PG 触发器函数：统一维护 update_time
CREATE OR REPLACE FUNCTION update_update_time_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_time = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION update_update_time_column() IS '通用更新时间触发器函数，更新行时自动刷新 update_time 字段';

-- 1. task
CREATE TABLE IF NOT EXISTS task (
    id              BIGINT NOT NULL PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    description     TEXT,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_task_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_task_status ON task(status, deleted) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_task_update_time ON task;
CREATE TRIGGER update_task_update_time BEFORE UPDATE ON task
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE task IS '任务主表';
COMMENT ON COLUMN task.id IS '主键ID';
COMMENT ON COLUMN task.title IS '任务标题';
COMMENT ON COLUMN task.description IS '任务描述';
COMMENT ON COLUMN task.status IS '任务状态：PENDING/IN_PROGRESS/DONE/CANCELLED';
COMMENT ON COLUMN task.create_by IS '创建人';
COMMENT ON COLUMN task.update_by IS '更新人';
COMMENT ON COLUMN task.create_time IS '创建时间';
COMMENT ON COLUMN task.update_time IS '更新时间';
COMMENT ON COLUMN task.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN task.remark IS '备注';

-- 2. module
CREATE TABLE IF NOT EXISTS module (
    id              BIGINT NOT NULL PRIMARY KEY,
    task_id         BIGINT       NOT NULL REFERENCES task(id),
    name            VARCHAR(255) NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_module_task ON module(task_id, deleted) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_module_update_time ON module;
CREATE TRIGGER update_module_update_time BEFORE UPDATE ON module
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE module IS '任务模块表';
COMMENT ON COLUMN module.id IS '主键ID';
COMMENT ON COLUMN module.task_id IS '所属任务ID';
COMMENT ON COLUMN module.name IS '模块名称';
COMMENT ON COLUMN module.sort_order IS '模块排序号';
COMMENT ON COLUMN module.create_by IS '创建人';
COMMENT ON COLUMN module.update_by IS '更新人';
COMMENT ON COLUMN module.create_time IS '创建时间';
COMMENT ON COLUMN module.update_time IS '更新时间';
COMMENT ON COLUMN module.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN module.remark IS '备注';

-- 3. agent
CREATE TABLE IF NOT EXISTS agent (
    id              BIGINT NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    api_key         VARCHAR(255),
    model_type      VARCHAR(64),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    score           INT          NOT NULL DEFAULT 0,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_agent_role CHECK (role IN ('PLANNER', 'EXECUTOR', 'REVIEWER', 'PATROL')),
    CONSTRAINT chk_agent_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE INDEX IF NOT EXISTS idx_agent_role ON agent(role, status) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_agent_update_time ON agent;
CREATE TRIGGER update_agent_update_time BEFORE UPDATE ON agent
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE agent IS '智能体配置表';
COMMENT ON COLUMN agent.id IS '主键ID';
COMMENT ON COLUMN agent.name IS '智能体名称';
COMMENT ON COLUMN agent.role IS '智能体角色：PLANNER/EXECUTOR/REVIEWER/PATROL';
COMMENT ON COLUMN agent.api_key IS '智能体访问密钥';
COMMENT ON COLUMN agent.model_type IS '所用模型类型';
COMMENT ON COLUMN agent.status IS '智能体状态：ACTIVE/DISABLED';
COMMENT ON COLUMN agent.score IS '当前积分';
COMMENT ON COLUMN agent.create_by IS '创建人';
COMMENT ON COLUMN agent.update_by IS '更新人';
COMMENT ON COLUMN agent.create_time IS '创建时间';
COMMENT ON COLUMN agent.update_time IS '更新时间';
COMMENT ON COLUMN agent.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN agent.remark IS '备注';

-- 4. sub_task
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
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_sub_task_status CHECK (
        status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS', 'REVIEW', 'DONE', 'REWORK', 'BLOCKED', 'CANCELLED')
    )
);
CREATE INDEX IF NOT EXISTS idx_sub_task_status ON sub_task(status, deleted) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sub_task_agent ON sub_task(assigned_agent, status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sub_task_deadline ON sub_task(deadline, status) WHERE status IN ('IN_PROGRESS', 'ASSIGNED');
CREATE INDEX IF NOT EXISTS idx_sub_task_score ON sub_task((score_factors->>'grade')) WHERE score_factors IS NOT NULL;
DROP TRIGGER IF EXISTS update_sub_task_update_time ON sub_task;
CREATE TRIGGER update_sub_task_update_time BEFORE UPDATE ON sub_task
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sub_task IS '子任务表';
COMMENT ON COLUMN sub_task.id IS '主键ID';
COMMENT ON COLUMN sub_task.task_id IS '所属任务ID';
COMMENT ON COLUMN sub_task.module_id IS '所属模块ID';
COMMENT ON COLUMN sub_task.title IS '子任务标题';
COMMENT ON COLUMN sub_task.status IS '子任务状态：PENDING/ASSIGNED/IN_PROGRESS/REVIEW/DONE/REWORK/BLOCKED/CANCELLED';
COMMENT ON COLUMN sub_task.assigned_agent IS '指派智能体ID';
COMMENT ON COLUMN sub_task.content IS '子任务内容';
COMMENT ON COLUMN sub_task.context IS '上下文信息(JSONB)';
COMMENT ON COLUMN sub_task.score_factors IS '评分因子(JSONB)';
COMMENT ON COLUMN sub_task.composite_score IS '综合评分';
COMMENT ON COLUMN sub_task.score_grade IS '评分等级';
COMMENT ON COLUMN sub_task.deadline IS '截止时间';
COMMENT ON COLUMN sub_task.version IS '乐观锁版本号';
COMMENT ON COLUMN sub_task.timeout_count IS '超时次数';
COMMENT ON COLUMN sub_task.create_by IS '创建人';
COMMENT ON COLUMN sub_task.update_by IS '更新人';
COMMENT ON COLUMN sub_task.create_time IS '创建时间';
COMMENT ON COLUMN sub_task.update_time IS '更新时间';
COMMENT ON COLUMN sub_task.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN sub_task.remark IS '备注';

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
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_review_result CHECK (result IN ('APPROVED', 'REJECTED'))
);
CREATE INDEX IF NOT EXISTS idx_review_sub_task ON review_record(sub_task_id, round);
DROP TRIGGER IF EXISTS update_review_update_time ON review_record;
CREATE TRIGGER update_review_update_time BEFORE UPDATE ON review_record
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE review_record IS '审查记录表';
COMMENT ON COLUMN review_record.id IS '主键ID';
COMMENT ON COLUMN review_record.sub_task_id IS '子任务ID';
COMMENT ON COLUMN review_record.reviewer_agent IS '审查智能体ID';
COMMENT ON COLUMN review_record.result IS '审查结果：APPROVED/REJECTED';
COMMENT ON COLUMN review_record.score IS '审查评分';
COMMENT ON COLUMN review_record.issues IS '问题描述';
COMMENT ON COLUMN review_record.comment IS '审查备注';
COMMENT ON COLUMN review_record.round IS '审查轮次';
COMMENT ON COLUMN review_record.create_by IS '创建人';
COMMENT ON COLUMN review_record.update_by IS '更新人';
COMMENT ON COLUMN review_record.create_time IS '创建时间';
COMMENT ON COLUMN review_record.update_time IS '更新时间';
COMMENT ON COLUMN review_record.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN review_record.remark IS '备注';

-- 6. reward_log
CREATE TABLE IF NOT EXISTS reward_log (
    id              BIGINT NOT NULL PRIMARY KEY,
    agent_id        BIGINT       NOT NULL,
    sub_task_id     BIGINT,
    reason          VARCHAR(255) NOT NULL,
    delta           INT          NOT NULL,
    balance         INT          NOT NULL,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_reward_agent ON reward_log(agent_id, create_time);
DROP TRIGGER IF EXISTS update_reward_log_update_time ON reward_log;
CREATE TRIGGER update_reward_log_update_time BEFORE UPDATE ON reward_log
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE reward_log IS '奖惩流水表';
COMMENT ON COLUMN reward_log.id IS '主键ID';
COMMENT ON COLUMN reward_log.agent_id IS '智能体ID';
COMMENT ON COLUMN reward_log.sub_task_id IS '关联子任务ID';
COMMENT ON COLUMN reward_log.reason IS '奖惩原因';
COMMENT ON COLUMN reward_log.delta IS '积分变动值';
COMMENT ON COLUMN reward_log.balance IS '变动后余额';
COMMENT ON COLUMN reward_log.create_by IS '创建人';
COMMENT ON COLUMN reward_log.update_by IS '更新人';
COMMENT ON COLUMN reward_log.create_time IS '创建时间';
COMMENT ON COLUMN reward_log.update_time IS '更新时间';
COMMENT ON COLUMN reward_log.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN reward_log.remark IS '备注';

-- 7. activity_log
CREATE TABLE IF NOT EXISTS activity_log (
    id              BIGINT NOT NULL PRIMARY KEY,
    agent_id        BIGINT,
    sub_task_id     BIGINT,
    action          VARCHAR(64)  NOT NULL,
    detail          JSONB,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_activity_agent ON activity_log(agent_id, create_time);
CREATE INDEX IF NOT EXISTS idx_activity_sub_task ON activity_log(sub_task_id);
DROP TRIGGER IF EXISTS update_activity_log_update_time ON activity_log;
CREATE TRIGGER update_activity_log_update_time BEFORE UPDATE ON activity_log
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE activity_log IS '行为日志表';
COMMENT ON COLUMN activity_log.id IS '主键ID';
COMMENT ON COLUMN activity_log.agent_id IS '智能体ID';
COMMENT ON COLUMN activity_log.sub_task_id IS '子任务ID';
COMMENT ON COLUMN activity_log.action IS '行为动作';
COMMENT ON COLUMN activity_log.detail IS '行为详情(JSONB)';
COMMENT ON COLUMN activity_log.create_by IS '创建人';
COMMENT ON COLUMN activity_log.update_by IS '更新人';
COMMENT ON COLUMN activity_log.create_time IS '创建时间';
COMMENT ON COLUMN activity_log.update_time IS '更新时间';
COMMENT ON COLUMN activity_log.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN activity_log.remark IS '备注';

-- 8. patrol_record
CREATE TABLE IF NOT EXISTS patrol_record (
    id              BIGINT NOT NULL PRIMARY KEY,
    sub_task_id     BIGINT       NOT NULL REFERENCES sub_task(id),
    patrol_agent    BIGINT       NOT NULL,
    alert_type      VARCHAR(64)  NOT NULL,
    description     TEXT,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_patrol_sub_task ON patrol_record(sub_task_id);
DROP TRIGGER IF EXISTS update_patrol_record_update_time ON patrol_record;
CREATE TRIGGER update_patrol_record_update_time BEFORE UPDATE ON patrol_record
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE patrol_record IS '巡检记录表';
COMMENT ON COLUMN patrol_record.id IS '主键ID';
COMMENT ON COLUMN patrol_record.sub_task_id IS '子任务ID';
COMMENT ON COLUMN patrol_record.patrol_agent IS '巡检智能体ID';
COMMENT ON COLUMN patrol_record.alert_type IS '预警类型';
COMMENT ON COLUMN patrol_record.description IS '预警描述';
COMMENT ON COLUMN patrol_record.create_by IS '创建人';
COMMENT ON COLUMN patrol_record.update_by IS '更新人';
COMMENT ON COLUMN patrol_record.create_time IS '创建时间';
COMMENT ON COLUMN patrol_record.update_time IS '更新时间';
COMMENT ON COLUMN patrol_record.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN patrol_record.remark IS '备注';

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
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_request_time ON request_log(create_time);
CREATE INDEX IF NOT EXISTS idx_request_id ON request_log(request_id);
DROP TRIGGER IF EXISTS update_request_log_update_time ON request_log;
CREATE TRIGGER update_request_log_update_time BEFORE UPDATE ON request_log
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE request_log IS '请求日志表';
COMMENT ON COLUMN request_log.id IS '主键ID';
COMMENT ON COLUMN request_log.request_id IS '请求唯一标识';
COMMENT ON COLUMN request_log.method IS 'HTTP方法';
COMMENT ON COLUMN request_log.path IS '请求路径';
COMMENT ON COLUMN request_log.params IS '请求参数(JSONB)';
COMMENT ON COLUMN request_log.response IS '响应内容(JSONB)';
COMMENT ON COLUMN request_log.duration IS '耗时(毫秒)';
COMMENT ON COLUMN request_log.ip IS '请求IP';
COMMENT ON COLUMN request_log.create_by IS '创建人';
COMMENT ON COLUMN request_log.update_by IS '更新人';
COMMENT ON COLUMN request_log.create_time IS '创建时间';
COMMENT ON COLUMN request_log.update_time IS '更新时间';
COMMENT ON COLUMN request_log.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN request_log.remark IS '备注';

-- 10. rule
CREATE TABLE IF NOT EXISTS rule (
    id              BIGINT NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    rule_type       VARCHAR(32)  NOT NULL,
    priority        INT          NOT NULL DEFAULT 0,
    content         TEXT         NOT NULL,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_rule_type CHECK (rule_type IN ('global', 'module', 'agent'))
);
CREATE INDEX IF NOT EXISTS idx_rule_type ON rule(rule_type, priority) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_rule_update_time ON rule;
CREATE TRIGGER update_rule_update_time BEFORE UPDATE ON rule
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE rule IS '规则配置表';
COMMENT ON COLUMN rule.id IS '主键ID';
COMMENT ON COLUMN rule.name IS '规则名称';
COMMENT ON COLUMN rule.rule_type IS '规则类型：global/module/agent';
COMMENT ON COLUMN rule.priority IS '优先级';
COMMENT ON COLUMN rule.content IS '规则内容';
COMMENT ON COLUMN rule.create_by IS '创建人';
COMMENT ON COLUMN rule.update_by IS '更新人';
COMMENT ON COLUMN rule.create_time IS '创建时间';
COMMENT ON COLUMN rule.update_time IS '更新时间';
COMMENT ON COLUMN rule.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN rule.remark IS '备注';

-- 11. agent_outbox_event
CREATE TABLE IF NOT EXISTS agent_outbox_event (
    id              BIGINT NOT NULL PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL UNIQUE,
    event_type      VARCHAR(64)  NOT NULL,
    routing_key     VARCHAR(128) NOT NULL,
    payload         JSONB        NOT NULL,
    status          SMALLINT     NOT NULL DEFAULT 0,
    retry_count     INT          NOT NULL DEFAULT 0,
    error_msg       TEXT,
    next_retry_time TIMESTAMPTZ,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_outbox_status CHECK (status IN (0, 1, 2))
);
CREATE INDEX IF NOT EXISTS idx_outbox_status_time ON agent_outbox_event(status, create_time);
CREATE INDEX IF NOT EXISTS idx_outbox_retry ON agent_outbox_event(status, next_retry_time);
DROP TRIGGER IF EXISTS update_outbox_update_time ON agent_outbox_event;
CREATE TRIGGER update_outbox_update_time BEFORE UPDATE ON agent_outbox_event
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE agent_outbox_event IS '智能体事件外发表';
COMMENT ON COLUMN agent_outbox_event.id IS '主键ID';
COMMENT ON COLUMN agent_outbox_event.event_id IS '事件唯一ID';
COMMENT ON COLUMN agent_outbox_event.event_type IS '事件类型';
COMMENT ON COLUMN agent_outbox_event.routing_key IS '消息路由键';
COMMENT ON COLUMN agent_outbox_event.payload IS '事件载荷(JSONB)';
COMMENT ON COLUMN agent_outbox_event.status IS '外发状态：0-PENDING，1-SUCCESS，2-FAILED';
COMMENT ON COLUMN agent_outbox_event.retry_count IS '重试次数';
COMMENT ON COLUMN agent_outbox_event.error_msg IS '失败错误信息';
COMMENT ON COLUMN agent_outbox_event.next_retry_time IS '下次重试时间';
COMMENT ON COLUMN agent_outbox_event.create_by IS '创建人';
COMMENT ON COLUMN agent_outbox_event.update_by IS '更新人';
COMMENT ON COLUMN agent_outbox_event.create_time IS '创建时间';
COMMENT ON COLUMN agent_outbox_event.update_time IS '更新时间';
COMMENT ON COLUMN agent_outbox_event.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN agent_outbox_event.remark IS '备注';

-- 12. conversation_archive
CREATE TABLE IF NOT EXISTS conversation_archive (
    id              BIGINT NOT NULL PRIMARY KEY,
    sub_task_id     BIGINT       NOT NULL REFERENCES sub_task(id),
    content         TEXT         NOT NULL,
    message_count   INT          NOT NULL DEFAULT 0,
    total_tokens    INT,
    archive_time    TIMESTAMPTZ  NOT NULL,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_archive_sub_task ON conversation_archive(sub_task_id);
CREATE INDEX IF NOT EXISTS idx_archive_time ON conversation_archive(archive_time);
DROP TRIGGER IF EXISTS update_conversation_archive_update_time ON conversation_archive;
CREATE TRIGGER update_conversation_archive_update_time BEFORE UPDATE ON conversation_archive
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE conversation_archive IS '对话归档表';
COMMENT ON COLUMN conversation_archive.id IS '主键ID';
COMMENT ON COLUMN conversation_archive.sub_task_id IS '子任务ID';
COMMENT ON COLUMN conversation_archive.content IS '归档内容';
COMMENT ON COLUMN conversation_archive.message_count IS '消息条数';
COMMENT ON COLUMN conversation_archive.total_tokens IS '总Token数';
COMMENT ON COLUMN conversation_archive.archive_time IS '归档时间';
COMMENT ON COLUMN conversation_archive.create_by IS '创建人';
COMMENT ON COLUMN conversation_archive.update_by IS '更新人';
COMMENT ON COLUMN conversation_archive.create_time IS '创建时间';
COMMENT ON COLUMN conversation_archive.update_time IS '更新时间';
COMMENT ON COLUMN conversation_archive.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN conversation_archive.remark IS '备注';

-- 13. attachment
CREATE TABLE IF NOT EXISTS attachment (
    id              BIGINT NOT NULL PRIMARY KEY,
    sub_task_id     BIGINT       REFERENCES sub_task(id),
    file_name       VARCHAR(255) NOT NULL,
    file_type       VARCHAR(64)  NOT NULL,
    mime_type       VARCHAR(128) NOT NULL,
    file_size       BIGINT       NOT NULL,
    bucket_name     VARCHAR(64)  NOT NULL DEFAULT 'helloai',
    object_key      VARCHAR(255) NOT NULL,
    storage_url     VARCHAR(500),
    preview_url     VARCHAR(500),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_attachment_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED'))
);
CREATE INDEX IF NOT EXISTS idx_attachment_sub_task ON attachment(sub_task_id, file_type);
CREATE INDEX IF NOT EXISTS idx_attachment_object ON attachment(bucket_name, object_key);
DROP TRIGGER IF EXISTS update_attachment_update_time ON attachment;
CREATE TRIGGER update_attachment_update_time BEFORE UPDATE ON attachment
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE attachment IS '附件信息表';
COMMENT ON COLUMN attachment.id IS '主键ID';
COMMENT ON COLUMN attachment.sub_task_id IS '关联子任务ID';
COMMENT ON COLUMN attachment.file_name IS '文件名';
COMMENT ON COLUMN attachment.file_type IS '业务文件类型';
COMMENT ON COLUMN attachment.mime_type IS 'MIME类型';
COMMENT ON COLUMN attachment.file_size IS '文件大小(字节)';
COMMENT ON COLUMN attachment.bucket_name IS '对象存储桶名称';
COMMENT ON COLUMN attachment.object_key IS '对象存储键';
COMMENT ON COLUMN attachment.storage_url IS '对象存储访问地址';
COMMENT ON COLUMN attachment.preview_url IS '预览地址';
COMMENT ON COLUMN attachment.status IS '附件状态：ACTIVE/INACTIVE/DELETED';
COMMENT ON COLUMN attachment.create_by IS '创建人';
COMMENT ON COLUMN attachment.update_by IS '更新人';
COMMENT ON COLUMN attachment.create_time IS '创建时间';
COMMENT ON COLUMN attachment.update_time IS '更新时间';
COMMENT ON COLUMN attachment.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN attachment.remark IS '备注';

-- 14. agent_execution_record
CREATE TABLE IF NOT EXISTS agent_execution_record (
    id              BIGINT NOT NULL PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL,
    sub_task_id     BIGINT       NOT NULL REFERENCES sub_task(id),
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    worker_node     VARCHAR(64),
    start_time      TIMESTAMPTZ,
    end_time        TIMESTAMPTZ,
    error_msg       TEXT,
    retry_count     INT          NOT NULL DEFAULT 0,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_exec_record_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'TIMEOUT'))
);
CREATE INDEX IF NOT EXISTS idx_exec_record_status_time ON agent_execution_record(status, create_time);
CREATE INDEX IF NOT EXISTS idx_exec_record_sub_task ON agent_execution_record(sub_task_id, status);
CREATE INDEX IF NOT EXISTS idx_exec_record_event ON agent_execution_record(event_id);
DROP TRIGGER IF EXISTS update_exec_record_update_time ON agent_execution_record;
CREATE TRIGGER update_exec_record_update_time BEFORE UPDATE ON agent_execution_record
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE agent_execution_record IS '智能体执行记录表';
COMMENT ON COLUMN agent_execution_record.id IS '主键ID';
COMMENT ON COLUMN agent_execution_record.event_id IS '事件ID';
COMMENT ON COLUMN agent_execution_record.sub_task_id IS '子任务ID';
COMMENT ON COLUMN agent_execution_record.status IS '执行状态：PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT';
COMMENT ON COLUMN agent_execution_record.worker_node IS '执行节点标识';
COMMENT ON COLUMN agent_execution_record.start_time IS '开始执行时间';
COMMENT ON COLUMN agent_execution_record.end_time IS '结束执行时间';
COMMENT ON COLUMN agent_execution_record.error_msg IS '错误信息';
COMMENT ON COLUMN agent_execution_record.retry_count IS '重试次数';
COMMENT ON COLUMN agent_execution_record.create_by IS '创建人';
COMMENT ON COLUMN agent_execution_record.update_by IS '更新人';
COMMENT ON COLUMN agent_execution_record.create_time IS '创建时间';
COMMENT ON COLUMN agent_execution_record.update_time IS '更新时间';
COMMENT ON COLUMN agent_execution_record.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN agent_execution_record.remark IS '备注';
