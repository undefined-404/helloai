-- ============================================
-- V44: task_iteration 增加 output_summary 列
-- ============================================
-- 分层摘要：从 LLM 执行产出末尾的 EXECUTION_RECORD 段
-- 解析 SUMMARY 行，存入本列供卡片摘要展示（≤200 字）。
-- 完整产出仍保留在 llm_response 中，展开查看。
-- ============================================

ALTER TABLE task_iteration
    ADD COLUMN output_summary TEXT;

COMMENT ON COLUMN task_iteration.output_summary IS '执行摘要（从 EXECUTION_RECORD SUMMARY 解析，≤200 字）';

-- ------------------------------------------------------------
-- Flyway 校验块
-- ------------------------------------------------------------
DO
$$
    BEGIN
        RAISE NOTICE '[V44] column output_summary added to task_iteration';
    END
$$;
