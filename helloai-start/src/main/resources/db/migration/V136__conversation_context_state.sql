-- ============================================================
-- V136: 会话上下文压缩状态（PA-12）
--
-- 用途：为助理对话 / 需求会话记录「上下文窗口」的压缩状态：
--   - compacted_until_seq：seq <= 该值的消息已折叠进 summary（原文仍保留，可回查）；
--   - summary / summary_version：滚动摘要（压缩后的「快速参考」）；
--   - trigger_ratio / reserve_ratio：自动压缩阈值与保留尾比例；
--   - context_size：模型上下文窗口快照；last_token_usage：上次模型用量（校准用）。
--
-- 设计：design/HelloAI 助理对话与上下文窗口设计.md §3.4 / §3.5。
-- 边界：压缩只改「喂给模型的上下文」，requirement_message 全量保留。
-- 红线：V1~V135 只读；仅新建表 + 索引，不改既有数据。
-- 回滚：DROP TABLE conversation_context_state;
-- ============================================================

CREATE TABLE IF NOT EXISTS conversation_context_state (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    conversation_id     BIGINT       NOT NULL,
    compacted_until_seq INTEGER      NOT NULL DEFAULT 0,
    summary             TEXT,
    summary_version     INTEGER      NOT NULL DEFAULT 0,
    trigger_ratio       NUMERIC(4,3) NOT NULL DEFAULT 0.800,
    reserve_ratio       NUMERIC(4,3) NOT NULL DEFAULT 0.200,
    enabled             INTEGER      NOT NULL DEFAULT 1,
    context_size        INTEGER,
    last_token_usage    INTEGER,
    owner_id            BIGINT,
    last_compact_at     TIMESTAMPTZ,
    deleted             SMALLINT     NOT NULL DEFAULT 0,
    create_by           VARCHAR(64)  NOT NULL DEFAULT '',
    update_by           VARCHAR(64)  NOT NULL DEFAULT '',
    create_time         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark              VARCHAR(255)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_context_state_conv
    ON conversation_context_state (conversation_id)
    WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_conversation_context_state_owner
    ON conversation_context_state (owner_id, deleted);

COMMENT ON TABLE conversation_context_state IS '会话上下文压缩状态（PA-12）：滚动摘要 + 已折叠到的 seq + 阈值配置';
COMMENT ON COLUMN conversation_context_state.conversation_id IS '所属会话 ID（逻辑唯一，软删后允许重建）';
COMMENT ON COLUMN conversation_context_state.compacted_until_seq IS 'seq <= 该值的消息已折叠进 summary（原文仍保留在 requirement_message）';
COMMENT ON COLUMN conversation_context_state.summary IS '滚动摘要（压缩后的快速参考，事实依据仍是原始消息）';
COMMENT ON COLUMN conversation_context_state.trigger_ratio IS '自动压缩阈值：总 token > context_size * trigger_ratio 时触发';
COMMENT ON COLUMN conversation_context_state.reserve_ratio IS '保留尾部比例：最近消息至少保留 context_size * reserve_ratio 的 token';
COMMENT ON COLUMN conversation_context_state.context_size IS '模型上下文窗口快照（null = 用全局配置）';
COMMENT ON COLUMN conversation_context_state.last_token_usage IS '上一次模型调用返回的 token 用量（校准参考）';

DROP TRIGGER IF EXISTS update_conversation_context_state_update_time ON conversation_context_state;
CREATE TRIGGER update_conversation_context_state_update_time
    BEFORE UPDATE ON conversation_context_state
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();