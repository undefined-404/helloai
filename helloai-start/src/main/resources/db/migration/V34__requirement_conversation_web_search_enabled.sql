-- V34: requirement_conversation 增加 web_search_enabled 列（会话级联网搜索开关）
-- NULL/true = 开启（默认开启），false = 关闭。
-- 触发条件：首轮 LLM 调用前（round_count=0）预检索行业资料，注入 {{WEB_SEARCH_CONTEXT}} 占位符。
-- 开会话时由用户开关决定；本列落库用于跨刷新/跨设备行为一致 + 老数据默认开启（兼容）。

ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS web_search_enabled BOOLEAN;

COMMENT ON COLUMN requirement_conversation.web_search_enabled IS
    '会话级联网搜索开关（Flyway V34 新增；NULL/true=开启，false=关闭。首轮对话前预检索行业资料/竞品/技术方案，注入 Prompt 增强拆解质量；失败一律降级跳过，不阻断澄清流程）';

-- 验证日志（启动时输出列是否就位；启动 V16/V17/V23 同款 DO $$ 验证块）
DO $$
DECLARE
    has_column BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'requirement_conversation'
          AND column_name = 'web_search_enabled'
    ) INTO has_column;
    IF has_column THEN
        RAISE NOTICE '[V34] requirement_conversation.web_search_enabled 列已就位';
    ELSE
        RAISE WARNING '[V34] requirement_conversation.web_search_enabled 列未创建，请检查迁移脚本';
    END IF;
END $$;
