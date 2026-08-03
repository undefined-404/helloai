-- V39: requirement_conversation 增加 mode 列（Planner 对话双模式：CHAT 自由对话 / CLARIFY 方案澄清）
-- NULL = 老数据兼容，读取侧按 CLARIFY 语义处理；新会话默认落库 'CHAT'（创建接口可传 initialMode 快捷直达 CLARIFY）。
-- 切换由用户主导：POST /{id}/to-clarify（切换 + 一轮 LLM 产草案/追问）、POST /{id}/to-chat（仅置位）。
-- CHAT 模式意图词命中（整理成方案等）时服务端自动切换 CLARIFY，该条消息即澄清首轮。

ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS mode VARCHAR(16);

ALTER TABLE requirement_conversation
    DROP CONSTRAINT IF EXISTS chk_requirement_conversation_mode;

ALTER TABLE requirement_conversation
    ADD CONSTRAINT chk_requirement_conversation_mode
        CHECK (mode IS NULL OR mode IN ('CHAT', 'CLARIFY'));

COMMENT ON COLUMN requirement_conversation.mode IS
    '对话模式（Flyway V39 新增；CHAT=自由对话 / CLARIFY=方案澄清，NULL=老数据按 CLARIFY 语义。切换由用户主导，意图词自动切换仅 CHAT→CLARIFY 单向）';

-- 验证日志（启动时输出列与约束是否就位；V34 同款 DO $$ 验证块）
DO $$
DECLARE
    has_column BOOLEAN;
    has_check BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'requirement_conversation'
          AND column_name = 'mode'
    ) INTO has_column;
    SELECT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_schema = 'public'
          AND table_name = 'requirement_conversation'
          AND constraint_name = 'chk_requirement_conversation_mode'
    ) INTO has_check;
    IF has_column AND has_check THEN
        RAISE NOTICE '[V39] requirement_conversation.mode 列与 CHECK 约束已就位';
    ELSE
        RAISE WARNING '[V39] requirement_conversation.mode 列或 CHECK 约束缺失，请检查迁移脚本';
    END IF;
END $$;
