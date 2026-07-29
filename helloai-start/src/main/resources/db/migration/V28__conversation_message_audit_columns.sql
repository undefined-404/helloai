-- ============================================================
-- V28__conversation_message_audit_columns.sql
-- 用途：conversation_message 补齐 update_by / update_time 审计列
-- 背景：
--   V1 建表时 conversation_message 缺少 update_by / update_time 两列，
--   而实体 ConversationMessage 继承 BaseEntity（updateBy/updateTime
--   为 INSERT_UPDATE 自动填充字段），MyBatis-Plus INSERT 会带上这两列，
--   直接写入会报"列不存在"。该表此前从未被业务写入（死表），
--   本轮激活为"子任务执行对话流"（Tier 2 工作记忆）前必须补列。
-- 机制：
--   - 补列 + 默认值，对齐仓库其余表的审计列规范
--   - 挂 update_update_time_column 触发器（与 review_record 等表一致）
-- ============================================================

ALTER TABLE conversation_message
    ADD COLUMN IF NOT EXISTS update_by VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE conversation_message
    ADD COLUMN IF NOT EXISTS update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

COMMENT ON COLUMN conversation_message.update_by IS '更新人';
COMMENT ON COLUMN conversation_message.update_time IS '更新时间';

DROP TRIGGER IF EXISTS update_conv_message_update_time ON conversation_message;
CREATE TRIGGER update_conv_message_update_time BEFORE UPDATE ON conversation_message
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V28] conversation_message 审计列 update_by/update_time 已补齐';
END $$;
