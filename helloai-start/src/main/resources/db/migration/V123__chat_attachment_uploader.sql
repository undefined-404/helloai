-- ============================================================
-- V123: chat_attachment 增加上传者用户 id（ATT-3 隔离补全）
--
-- 背景（2026-10-23）：V121 建会话附件表时只记 clientToken（新会话「先上传后建会」
-- 的临时归属，前端 UUID），没有登录用户 id。ATT-3 把超预算附件 ingest 到 kb 时
-- 需要把「谁上传的」落到 kb_chunk.user_id，否则检索隔离只能靠会话维度——
-- 同一会话多用户场景（共享会话 / 管理端代看）无法区分。
--
-- 上传端点已有 @SaCheckPermission("conversation:add")，登录态必然存在，
-- 故新数据一律写值；历史数据留空（检索侧按 IS NULL 兼容）。
--
-- 红线：V1~V122 只读，本迁移递增为 V123；仅加可空列 + 索引，不改既有数据。
-- 回滚：DROP INDEX idx_chat_attachment_uploader；ALTER TABLE chat_attachment DROP COLUMN uploader_user_id。
-- ============================================================

ALTER TABLE chat_attachment ADD COLUMN IF NOT EXISTS uploader_user_id BIGINT;

COMMENT ON COLUMN chat_attachment.uploader_user_id IS '上传者登录用户 id（ATT-3）：上传接口必填；历史数据为 NULL';

-- 管理端「某用户上传了哪些附件」与按用户清理走这条索引
CREATE INDEX IF NOT EXISTS idx_chat_attachment_uploader
    ON chat_attachment (uploader_user_id, deleted);
