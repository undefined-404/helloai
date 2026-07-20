-- ============================================================
-- V24__sub_task_reassign_attempt_count.sql
-- 用途：子任务重分配熔断 —— 防止无限重分配死循环
-- 背景：
--   当同角色所有 Agent 均掉线时，AgentHealthCheckTask 检测到 OFFLINE
--   后触发 redispatchOfflineSubTask → ResilientDispatcher fast-fail
--   → fallback 选不到替代 Agent → 子任务退回 PENDING → 下一个周期
--   再次被 ExternalAgentFallbackTask 或 Poller 捡起重试 → 死循环。
--   本迁移新增 reassign_attempt_count 列，由 SubTaskDispatchService 在
--   每次重分配入口累加；达到 max-reassign-attempts（默认 5）后直接
--   取消子任务（status=CANCELLED），打破循环。
-- 机制：
--   - 每次 redispatchOfflineSubTask / redispatchAssignedTimeout /
--     redispatchForFallback / dispatchBlockedSubTask 调用时累加 1
--   - 累计达到阈值后不再尝试重分配，直接标记 CANCELLED
--   - CANCELLED 视为终态，所有周期补偿任务都会跳过
--   - 管理员可从 UI 手动恢复（重置计数 + 重新激活）
-- 参考：用户反馈 2026-07-20 — "sub-task-002 无限重分配，Agent 全掉线"
-- ============================================================

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS reassign_attempt_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sub_task.reassign_attempt_count IS '子任务重分配尝试次数（所有类型的重分配都计数，达到阈值后自动取消子任务）';

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V24] sub_task.reassign_attempt_count 列已就绪';
END $$;
