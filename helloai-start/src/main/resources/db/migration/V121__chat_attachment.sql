-- V121__chat_attachment.sql
-- 目的：需求对话「添加文件 / 引用文件」（P0）——会话级附件表与消息关联表。
-- 方案：doc/plan/HelloAI 需求对话附件设计方案（2026-10-22）.md §3.1
-- 日志：LOG-20261022-008（预研）/ 011（解析分工定稿）
--
-- 为什么不复用既有 attachment 表：
--   1) 语义锚点不同 —— attachment 的可见性策略（AttachmentVisibilityPolicy）、交付物判定、
--      补偿任务全部建立在「子任务」上；塞进会话维度会让判据出现第四种情形。
--   2) 生命周期不同 —— 会话附件随会话删除回收；子任务附件属交付审计，需长期留存。
--   3) 迁移零风险 —— 新表 + 新端点，不动既有 T1/T2 链路。
--
-- client_token 是刚需：新会话是「先上传文件 → 再发第一句」的顺序，此时 conversation_id 还不存在。
--   前端生成 UUID 占位，建会时按 token 认领并回填 conversation_id（认领后 token 保留，便于幂等重试）。
--
-- 解析状态机：PENDING → PARSING → READY / FAILED / SKIPPED
--   SKIPPED 是显式「读不出正文」（图片未接通视觉、扫描件未 OCR），与 FAILED 区分：
--   FAILED 可重试，SKIPPED 重试也无用，Prompt 清单里必须写明，不许模型臆测正文。
--
-- 回滚：DROP TABLE requirement_message_attachment; DROP TABLE chat_attachment;

CREATE TABLE chat_attachment (
    id              bigint       PRIMARY KEY,
    conversation_id bigint,
    client_token    varchar(64),
    file_name       varchar(255) NOT NULL,
    file_type       varchar(64)  NOT NULL DEFAULT '',
    mime_type       varchar(128) NOT NULL DEFAULT 'application/octet-stream',
    file_size       bigint       NOT NULL DEFAULT 0,
    content_hash    varchar(64),
    bucket_name     varchar(64)  NOT NULL DEFAULT '',
    object_key      varchar(255) NOT NULL DEFAULT '',
    parse_status    varchar(16)  NOT NULL DEFAULT 'PENDING',
    parse_error     varchar(512),
    extracted_text  text,
    extracted_chars integer,
    page_count      integer,
    parser          varchar(32),
    charset_name    varchar(32),
    kb_source       varchar(160),
    deleted         smallint     NOT NULL DEFAULT 0,
    create_by       varchar(64)  NOT NULL DEFAULT 'system',
    update_by       varchar(64)  NOT NULL DEFAULT 'system',
    create_time     timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark          varchar(255)
);

COMMENT ON TABLE chat_attachment IS '需求对话附件（会话级，与子任务级 attachment 分表）';
COMMENT ON COLUMN chat_attachment.client_token IS '新会话首句前的临时归属（前端 UUID），建会时按此认领回填 conversation_id';
COMMENT ON COLUMN chat_attachment.content_hash IS 'sha256，同一会话内去重（重复上传不重复解析/嵌入）';
COMMENT ON COLUMN chat_attachment.parse_status IS 'PENDING/PARSING/READY/FAILED/SKIPPED';
COMMENT ON COLUMN chat_attachment.extracted_text IS '解析结果正文（文本族原文；结构化格式为 Markdown）；超限额已截断并标注';
COMMENT ON COLUMN chat_attachment.parser IS '解析器标识：text / tika / vision（后续）；便于排障与口径统计';
COMMENT ON COLUMN chat_attachment.charset_name IS '文本族判码结果（UTF-8/GB18030/…），排查乱码用';
COMMENT ON COLUMN chat_attachment.kb_source IS '已入 RAG 时的 source（chat:{conversationId}:{attachmentId}）';

CREATE INDEX idx_chat_att_conv ON chat_attachment (conversation_id) WHERE deleted = 0;
CREATE INDEX idx_chat_att_token ON chat_attachment (client_token) WHERE conversation_id IS NULL AND deleted = 0;
CREATE UNIQUE INDEX uk_chat_att_hash ON chat_attachment (conversation_id, content_hash)
    WHERE deleted = 0 AND conversation_id IS NOT NULL AND content_hash IS NOT NULL;

CREATE TABLE requirement_message_attachment (
    id            bigint      PRIMARY KEY,
    message_id    bigint      NOT NULL,
    attachment_id bigint      NOT NULL,
    seq           integer     NOT NULL DEFAULT 0,
    create_time   timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE requirement_message_attachment IS '需求消息 ↔ 会话附件的引用关系（一条消息可引多文件，按 seq 保持上传顺序）';

CREATE UNIQUE INDEX uk_msg_att ON requirement_message_attachment (message_id, attachment_id);
CREATE INDEX idx_msg_att_att ON requirement_message_attachment (attachment_id);
