-- ============================================================
-- V135: 助理对话地基（PA-11 步骤1）
--
-- 用途：为「个人工作台中间的助理对话」落数据地基：
--   1) requirement_conversation.kind：会话类型（REQUIREMENT / ASSISTANT），
--      助理会话与需求会话共用同一套「会话 + 消息」存储，按 kind 分流行为；
--   2) 从对话创建任务的血缘列：parent_conversation_id / forked_from_message_id；
--   3) 一期跨历史检索索引：pg_trgm GIN（requirement_message.content /
--      requirement_conversation.title）。扩展不可用时降级为 ILIKE 扫描。
--
-- 设计口径：见 design/HelloAI 助理对话与上下文窗口设计.md §3.1 / §3.5。
-- 兼容：kind 默认 REQUIREMENT，老数据自动归为需求会话，行为不变。
-- 红线：V1~V134 只读；仅加列 + 索引，不改既有数据（CODE_STYLE §16.1）。
-- 回滚：DROP INDEX 两条；ALTER TABLE DROP COLUMN 三列（互相独立）。
-- ============================================================

ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'REQUIREMENT';
ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS parent_conversation_id BIGINT;
ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS forked_from_message_id BIGINT;

COMMENT ON COLUMN requirement_conversation.kind IS '会话类型（PA-11）：REQUIREMENT 需求澄清 / ASSISTANT 助理对话';
COMMENT ON COLUMN requirement_conversation.parent_conversation_id IS '建任务血缘（PA-11）：从哪个助理会话创建（软引用无 FK）';
COMMENT ON COLUMN requirement_conversation.forked_from_message_id IS '建任务血缘（PA-11）：从哪条消息创建（软引用无 FK）';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_requirement_conversation_kind'
    ) THEN
        ALTER TABLE requirement_conversation
            ADD CONSTRAINT chk_requirement_conversation_kind
            CHECK (kind IN ('REQUIREMENT', 'ASSISTANT'));
    END IF;
END $$;

-- 一期跨历史检索：无权限的精简 PG 建不了 pg_trgm 时跳过索引，
-- 应用层回退 ILIKE 扫描（不阻断启动）。
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'pg_trgm 不可用，跳过 trigram 索引（一期降级 ILIKE）';
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_requirement_message_content_trgm
            ON requirement_message USING gin (content gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_requirement_conversation_title_trgm
            ON requirement_conversation USING gin (title gin_trgm_ops);
    END IF;
END $$;