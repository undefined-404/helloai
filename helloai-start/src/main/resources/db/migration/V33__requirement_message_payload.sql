-- V33: requirement_message 增加 payload 列（结构化澄清协议）
-- 一列两用：assistant 行存结构化问题 JSON（{"mode","progress","questions":[...]}），
-- user 行存选择快照 JSON（{"selections":[...]}）；纯文本消息为 NULL。
-- 用 TEXT 而非 JSONB：与 V32 同款约定，避免 MyBatis-Plus 写入链引入
-- JacksonTypeHandler + XML 覆盖改造；payload 只做整存整取，无库内查询需求。

ALTER TABLE requirement_message
    ADD COLUMN IF NOT EXISTS payload TEXT;

COMMENT ON COLUMN requirement_message.payload IS '结构化附加数据（JSON 文本；assistant=结构化问题，user=选择快照；NULL=纯文本消息）';
