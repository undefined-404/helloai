-- V40: requirement_conversation 增加 pending_clarify_confirm 列（意图词二次确认标记）
-- V39 意图词命中即自动切 CLARIFY，用户反馈缺少"转方案前确认"环节；V40 改为：
--   1) CHAT 模式命中意图词（整理成方案等）→ 本列置 1 + 回复固定确认询问（不调 LLM、不加轮数）；
--   2) 用户回复确认词（确认/好的/开始吧等）或再次表达意图 → 转入 CLARIFY（该条消息即澄清首轮）并清零；
--   3) 用户回复其他内容 → 清零并继续自由对话。
-- 0=无待确认（默认），1=意图词已命中等待用户确认。布尔语义按代码规范 9.3 用 SMALLINT（0/1），不用 BOOLEAN。

ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS pending_clarify_confirm SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN requirement_conversation.pending_clarify_confirm IS
    '意图词二次确认标记（Flyway V40 新增；0=无待确认 / 1=意图词已命中等待用户确认。用户确认词回复后转入 CLARIFY 并清零，确认询问不消耗对话轮数）';

-- 验证日志（启动时输出列与默认值是否就位；V34/V39 同款 DO $$ 验证块）
DO $$
DECLARE
    has_column BOOLEAN;
    has_default BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'requirement_conversation'
          AND column_name = 'pending_clarify_confirm'
    ) INTO has_column;
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'requirement_conversation'
          AND column_name = 'pending_clarify_confirm'
          AND column_default IS NOT NULL
    ) INTO has_default;
    IF has_column AND has_default THEN
        RAISE NOTICE '[V40] requirement_conversation.pending_clarify_confirm 列与默认值已就位';
    ELSE
        RAISE WARNING '[V40] requirement_conversation.pending_clarify_confirm 列或默认值缺失，请检查迁移脚本';
    END IF;
END $$;
