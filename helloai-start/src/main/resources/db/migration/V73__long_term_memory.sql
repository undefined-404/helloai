-- ============================================================
-- V73__long_term_memory.sql
-- 用途：长期记忆平面（N-009，Phase 2 C5-S1）
-- 背景：
--   Planner 已具备会话上下文（requirement_conversation/message）；差距表 N-009 要求
--   独立长期记忆平面，原则「不要把所有历史 Conversation 直接当 Memory」。
--   N-009 首版 = 摘要式记忆：会话 finalize 生成摘要归档（非原始对话）+ 受控 recall 注入
--   （C5 设计 §3.1/§3.2/§3.3）。
-- 关键设计：
--   - content 只存压缩摘要（原则兑现），不存原始 message 列表
--   - type SESSION/TASK/USER，ref_type+ref_id 唯一（同一来源只归档一次）
--   - tag 供 recall 关键词匹配
-- 参考：doc/design/HelloAI_Phase2_C5_跨会话记忆设计预研.md §3.1
-- ============================================================

CREATE TABLE IF NOT EXISTS long_term_memory (
    id           BIGINT       NOT NULL PRIMARY KEY,
    type         VARCHAR(16)  NOT NULL,
    scope_key    VARCHAR(128) NOT NULL,
    title        VARCHAR(255),
    content      TEXT         NOT NULL,
    ref_type     VARCHAR(32)  NOT NULL,
    ref_id       BIGINT       NOT NULL,
    tag          VARCHAR(64),
    memory_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by    VARCHAR(64)  NOT NULL DEFAULT '',
    update_by    VARCHAR(64)  NOT NULL DEFAULT '',
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    remark       VARCHAR(255),
    CONSTRAINT chk_long_term_memory_type CHECK (type IN ('SESSION', 'TASK', 'USER')),
    CONSTRAINT uk_long_term_memory_ref UNIQUE (ref_type, ref_id)
);
CREATE INDEX IF NOT EXISTS idx_long_term_memory_tag
    ON long_term_memory(tag, memory_time) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_long_term_memory_update_time ON long_term_memory;
CREATE TRIGGER update_long_term_memory_update_time BEFORE UPDATE ON long_term_memory
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE long_term_memory IS '长期记忆平面（N-009，C5 设计：摘要式记忆，非原始对话；受控 recall 注入）';
COMMENT ON COLUMN long_term_memory.type IS '记忆类型：SESSION / TASK / USER';
COMMENT ON COLUMN long_term_memory.scope_key IS '归属：conversation:{id} / task:{id} / user:{id}';
COMMENT ON COLUMN long_term_memory.content IS '摘要正文（压缩摘要，不存原始对话）';
COMMENT ON COLUMN long_term_memory.ref_type IS '来源引用：REQUIREMENT_CONVERSATION / TASK_RUNNING_SPEC';
COMMENT ON COLUMN long_term_memory.ref_id IS '来源实体 id（ref_type+ref_id 唯一，防重复归档）';
COMMENT ON COLUMN long_term_memory.tag IS '关键词/主题（recall 匹配用）';
