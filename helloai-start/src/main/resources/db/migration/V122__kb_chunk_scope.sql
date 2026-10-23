-- ============================================================
-- V122: kb_chunk 增加会话 / 用户作用域（ATT-3 前置，内存隔离）
--
-- 背景（2026-10-23）：ATT-3「大文件 RAG + 引用」要把超预算的会话附件 ingest 到
-- kb，检索时按「本轮选中的附件 + 会话 + 用户」三重过滤。但 V107 建表时只有
-- source（`chat:{conv}:{att}` 命名约定），没有 conversation_id / user_id 列：
--   - 检索隔离只能靠 `source LIKE 'chat:<conv>:%'` 前缀，prefix LIKE 在简体中文
--     collation 下不走普通 btree，需 text_pattern_ops，且表达不了「谁上传的」；
--   - 更要紧的是 source 是命名约定、不是访问控制：写错前缀即跨会话泄漏。
--
-- 本迁移只加列 + 回填 + 索引，不改既有行为（现有 3 条非 chat 前缀数据不受影响）。
--
-- 回填口径：source 形如 `chat:{conversationId}:{attachmentId}`，用 split_part 取第 2 段；
--   正则 `^[0-9]+$` 兜住非 chat 前缀与格式异常，避免 CAST 失败中断迁移。
-- user_id 无法从 source 解析，留空由 ingest 写入（历史数据为空即可，检索侧按
--   `user_id IS NULL OR user_id = :uid` 兼容）。
--
-- 红线：V1~V121 只读，本迁移递增为 V122；仅 ALTER + UPDATE + CREATE INDEX，不动数据语义。
-- 回滚：DROP INDEX idx_kb_chunk_scope / idx_kb_chunk_source；
--       ALTER TABLE kb_chunk DROP COLUMN conversation_id, DROP COLUMN user_id；
--       回填的 UPDATE 无需回滚（列删除即失效）。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 加列（可空：历史非会话数据无归属）
-- ------------------------------------------------------------
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS conversation_id BIGINT;
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS user_id BIGINT;

COMMENT ON COLUMN kb_chunk.conversation_id IS '会话 id（ATT-3）：source 为 chat:{conv}:{att} 时回填，非会话知识库为 NULL';
COMMENT ON COLUMN kb_chunk.user_id IS '上传者 id（ATT-3）：ingest 时写入；历史数据为 NULL';

-- ------------------------------------------------------------
-- 2) 回填会话维度（仅 chat: 前缀且第 2 段为纯数字）
-- ------------------------------------------------------------
UPDATE kb_chunk
SET conversation_id = CAST(split_part(source, ':', 2) AS BIGINT)
WHERE deleted = 0
  AND conversation_id IS NULL
  AND source LIKE 'chat:%'
  AND split_part(source, ':', 2) ~ '^[0-9]+$';

-- ------------------------------------------------------------
-- 3) 检索索引
--    复合索引按实际过滤顺序排：(conversation_id, deleted) —— 检索永远带会话 + 未删除
--    source 索引服务「按附件精确检索 / 按会话清理」两条路径
-- ------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_kb_chunk_scope
    ON kb_chunk (conversation_id, deleted);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_source
    ON kb_chunk (source);
