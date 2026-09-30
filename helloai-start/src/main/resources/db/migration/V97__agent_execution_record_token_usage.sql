-- ============================================================
-- V97__agent_execution_record_token_usage.sql
-- 用途：tokenUsage 采集落库（审计 §12.3 P2 / §11.7 #3，B5 token 成本观测清零）
-- 背景：
--   现状：AgentLoop 不采集 usage → AgentExecutionResult.tokenUsage 恒 null →
--         agent_execution_record 表无 token 列，Fleet 成本观测为零。
--   目标：loop 每轮读取 ChatResponse.metadata.usage.totalTokens 并累加，
--         随执行终态 CAS（markSuccess / markFailed）写入本列，
--         作为 Agent Fleet 成本化与选人策略的结构化数据前提。
--   字段语义：
--     token_usage  本次执行全部轮次 totalTokens 累加；
--                  NULL = provider 未返回 usage（best-effort，不阻断执行）
-- 版本说明：创建时现网已应用至 V96，本迁移取下一个顺序版本 V97。
-- 参考：doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-30） §12.3 / §11.7
-- ============================================================

ALTER TABLE agent_execution_record
    ADD COLUMN IF NOT EXISTS token_usage INTEGER;

COMMENT ON COLUMN agent_execution_record.token_usage IS '本次执行 Token 用量（全部轮次 totalTokens 累加；NULL = provider 未返回 usage 或未统计）';

-- 验证日志（启动时输出列存在性）
DO $$
DECLARE
    present_columns INTEGER;
BEGIN
    SELECT COUNT(*) INTO present_columns
    FROM information_schema.columns
    WHERE table_name = 'agent_execution_record'
      AND column_name = 'token_usage';
    RAISE NOTICE '[V97] agent_execution_record token_usage 列补全完成，已存在相关列数 = %', present_columns;
END $$;
