-- ============================================================
-- V29__requirement_clarify.sql
-- 用途：对话式需求澄清窗口（第二步立项）两张新表
-- 背景：
--   conversation_message.sub_task_id 为 NOT NULL 且带外键，无法承载
--   "任务创建前"的澄清对话（那时还没有 sub_task），故新建独立表。
--   会话历史服务端持有，前端只带 conversationId。
-- 设计：
--   - requirement_conversation.task_id 为软引用（不加 FK）：避免扩大
--     deleteTaskCascade 的 FK 引用面，删任务后允许悬挂，审计仍可追溯
--   - 主键为应用侧雪花（MyBatis-Plus ASSIGN_ID），不用序列
-- ============================================================

CREATE TABLE IF NOT EXISTS requirement_conversation (
    id                  BIGINT         NOT NULL PRIMARY KEY,
    title               VARCHAR(200)   NOT NULL DEFAULT '',
    status              VARCHAR(32)    NOT NULL DEFAULT 'ACTIVE',
    task_id             BIGINT,
    final_title         VARCHAR(200),
    final_description   TEXT,
    round_count         INT            NOT NULL DEFAULT 0,
    deleted             SMALLINT       NOT NULL DEFAULT 0,
    create_by           VARCHAR(64)    NOT NULL DEFAULT 'system',
    update_by           VARCHAR(64)    NOT NULL DEFAULT 'system',
    create_time         TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark              VARCHAR(255),
    CONSTRAINT chk_requirement_conversation_status CHECK (
        status IN ('ACTIVE', 'FINALIZED', 'ABANDONED')
    )
);

-- 会话列表主查询路径：按状态 + 创建时间倒序；partial index 排除软删行
CREATE INDEX IF NOT EXISTS idx_req_conv_status_create
    ON requirement_conversation(status, create_time)
    WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_requirement_conversation_update_time ON requirement_conversation;
CREATE TRIGGER update_requirement_conversation_update_time
    BEFORE UPDATE ON requirement_conversation
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE requirement_conversation IS '需求澄清会话（对话式新建任务入口）';
COMMENT ON COLUMN requirement_conversation.id IS '主键ID（应用侧雪花）';
COMMENT ON COLUMN requirement_conversation.title IS '会话标题（首条用户消息截断）';
COMMENT ON COLUMN requirement_conversation.status IS '会话状态: ACTIVE 进行中 / FINALIZED 已终稿建任务 / ABANDONED 已放弃';
COMMENT ON COLUMN requirement_conversation.task_id IS '终稿确认后创建的任务 ID（软引用无 FK，删任务后允许悬挂）';
COMMENT ON COLUMN requirement_conversation.final_title IS 'LLM 最近一次终稿的任务标题（等用户确认）';
COMMENT ON COLUMN requirement_conversation.final_description IS 'LLM 最近一次终稿的需求描述（等用户确认）';
COMMENT ON COLUMN requirement_conversation.round_count IS '用户消息轮数（服务端硬上限防失控）';

CREATE TABLE IF NOT EXISTS requirement_message (
    id                  BIGINT         NOT NULL PRIMARY KEY,
    conversation_id     BIGINT         NOT NULL REFERENCES requirement_conversation(id),
    role                VARCHAR(16)    NOT NULL,
    content             TEXT           NOT NULL,
    seq                 INT            NOT NULL,
    deleted             SMALLINT       NOT NULL DEFAULT 0,
    create_by           VARCHAR(64)    NOT NULL DEFAULT 'system',
    update_by           VARCHAR(64)    NOT NULL DEFAULT 'system',
    create_time         TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark              VARCHAR(255),
    CONSTRAINT chk_requirement_message_role CHECK (
        role IN ('user', 'assistant')
    )
);

-- 会话消息回放主路径：按会话 + 序号升序
CREATE INDEX IF NOT EXISTS idx_req_msg_conv_seq
    ON requirement_message(conversation_id, seq);

DROP TRIGGER IF EXISTS update_requirement_message_update_time ON requirement_message;
CREATE TRIGGER update_requirement_message_update_time
    BEFORE UPDATE ON requirement_message
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE requirement_message IS '需求澄清会话消息';
COMMENT ON COLUMN requirement_message.id IS '主键ID（应用侧雪花）';
COMMENT ON COLUMN requirement_message.conversation_id IS '所属澄清会话 ID';
COMMENT ON COLUMN requirement_message.role IS '消息角色: user 用户 / assistant LLM 需求分析师';
COMMENT ON COLUMN requirement_message.content IS '消息正文';
COMMENT ON COLUMN requirement_message.seq IS '会话内序号（从 1 递增）';
