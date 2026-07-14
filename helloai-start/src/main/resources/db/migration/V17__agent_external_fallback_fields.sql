-- ============================================================
-- V17__agent_external_fallback_fields.sql
-- 用途：N11 外部 Agent 阈值回退 LLM — Phase 2C §5.2
-- 背景：
--   现状：调度策略"外部优先 + 空闲优先 + LLM 保底"在分配入口已经具备（preferExternal /
--         requireIdle / forceAccessType），但缺少"外部 Agent 失败次数达到阈值后自动回退
--         到平台内 API_KEY_LLM"的闭环。
--   目标：补齐失败计数 + 回退冷却字段，让 ExternalAgentFallbackTask 可以周期性扫描超阈值
--         CLI_CLIENT Agent，并将其在跑子任务重新分配到 API_KEY_LLM 类型的 EXECUTOR。
--   字段语义：
--     agent.consecutive_failure_count
--                  连续失败次数（成功一次清零；>= failureThreshold 触发回退候选）
--     agent.last_failure_at
--                  最近一次失败时间（与 consecutive_failure_count 同步刷新）
--     agent.last_fallback_at
--                  最近一次回退触发时间（用于 cooldown 判定，避免刚回退的 Agent
--                  在 cooldown 期间被反复触发）
--     sub_task.external_fallback_count
--                  当前子任务已发生的回退次数（包含 sub_task_dispatch_prepare trigger=
--                  external_fallback 的轮次；冗余计数便于聚合统计）
-- 计数来源：
--   - ExecutionResultHandler.handleReport(!success)        → 累加 1
--   - ExecutionCompensationTask.compensateRecordAtomically → 累加 1（仅 CLI_CLIENT）
--   - AgentHealthCheckTask.processStaleAgent              → 累加 1（仅 CLI_CLIENT）
--   - 上述任一路径 success / 恢复                         → 重置为 0
--   触发回退的判定由 helloai-job 中新增的 ExternalAgentFallbackTask 周期执行。
-- 参考：架构设计参考 §5.2 / 调度解耦重构分析 §7 阶段 3
-- ============================================================

ALTER TABLE agent
    ADD COLUMN IF NOT EXISTS consecutive_failure_count INT          NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_failure_at             TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_fallback_at            TIMESTAMPTZ;

COMMENT ON COLUMN agent.consecutive_failure_count IS '连续失败次数；成功清零；>= threshold 触发回退候选';
COMMENT ON COLUMN agent.last_failure_at            IS '最近一次失败时间（与 consecutive_failure_count 同步刷新）';
COMMENT ON COLUMN agent.last_fallback_at           IS '最近一次回退触发时间；用于 cooldown 判定';

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS external_fallback_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sub_task.external_fallback_count IS '当前子任务已发生的"外部→LLM"回退次数';

-- 回退扫描专用索引：仅 CLI_CLIENT + 未删除 + 失败次数>0 的 Agent 才有意义
-- 部分索引让扫描只命中候选行，避免对其它 Agent / 状态做全表
CREATE INDEX IF NOT EXISTS idx_agent_external_failure_scan
    ON agent(consecutive_failure_count, last_fallback_at)
    WHERE access_type = 'CLI_CLIENT' AND deleted = 0;

-- 验证日志
DO $$
DECLARE
    added_agent_cols INTEGER;
    added_sub_cols   INTEGER;
BEGIN
    SELECT COUNT(*) INTO added_agent_cols
    FROM information_schema.columns
    WHERE table_name = 'agent'
      AND column_name IN ('consecutive_failure_count', 'last_failure_at', 'last_fallback_at');

    SELECT COUNT(*) INTO added_sub_cols
    FROM information_schema.columns
    WHERE table_name = 'sub_task'
      AND column_name = 'external_fallback_count';

    RAISE NOTICE '[V17] N11 阈值回退字段补全完成: agent 新增列 = %, sub_task 新增列 = %',
        added_agent_cols, added_sub_cols;
END $$;
