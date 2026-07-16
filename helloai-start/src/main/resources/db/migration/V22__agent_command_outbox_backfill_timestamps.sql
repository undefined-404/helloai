-- ============================================================
-- V22: 回填 agent_command_outbox 历史 SENT / CONFIRMED 行的时间戳
-- ------------------------------------------------------------
-- 背景：Phase 2H ②b 在 V20 新增 last_sent_at / confirmed_at 两列，
--   用于 Publisher Confirms + Retry 异步回写。
--   但 V20 仅做了"加列 + 加索引"，未对历史已落库的 SENT(1) / CONFIRMED(3) 行回填时间戳：
--     - idx_agent_command_outbox_sent_scan 是 partial index WHERE status=1 AND deleted=0，
--       扫描历史 SENT 行时 last_sent_at 全为 NULL，靠 update_time 兜底扫描；
--     - 历史 CONFIRMED(3) 行的 confirmed_at 同样全 NULL，
--       ConfirmCallback 当前是 upsert，不依赖历史回填，但报表/审计受影响。
--
-- 回填策略（保守近似）：
--   1) status=1 (SENT) 且 last_sent_at IS NULL  →  backfill = update_time
--      OutboxRelayTask markSent 的唯一动作就是 "status=1, last_sent_at=now(), update_time=now()"
--      触发器再写一次 update_time，所以 update_time ≈ last_sent_at；
--      这是一致性近似，不是精确投递时间，但对监控/对账足够。
--   2) status=3 (CONFIRMED) 且 confirmed_at IS NULL → backfill = update_time
--      ConfirmCallback 的写动作 = "status=3, confirmed_at=now()" 同步 update_time。
--   3) status=2 (FAILED) 不回填 last_sent_at：
--      FAILED 语义可能是 publish 阶段失败（last_sent_at 不应被赋值），也可能是 broker NACK，
--      两种情况下 last_sent_at 是否置值历史上不一致，回填会造成更大歧义，保持 NULL。
--   4) status=0 (PENDING) 不动：last_sent_at/confirmed_at 语义上未发生。
--
-- 幂等性：所有 UPDATE 都有 WHERE last_sent_at IS NULL / confirmed_at IS NULL 守卫，
-- 重跑安全（V23 之前若要再修，可重复执行而不会覆盖真实值）。
--
-- 前置：依赖 V20 已将 status 从 VARCHAR 转为 SMALLINT。
-- ============================================================

UPDATE agent_command_outbox
SET last_sent_at = update_time
WHERE status = 1
  AND deleted = 0
  AND last_sent_at IS NULL;

UPDATE agent_command_outbox
SET confirmed_at = update_time
WHERE status = 3
  AND deleted = 0
  AND confirmed_at IS NULL;