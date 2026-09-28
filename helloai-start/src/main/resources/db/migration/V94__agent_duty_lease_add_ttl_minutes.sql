-- ============================================================
-- V94__agent_duty_lease_add_ttl_minutes.sql
-- 用途：值班租约持久化**签发窗口**（G-015 B4.3 / P2-10）
-- 背景：
--   P2-10：agent_duty_lease 未持久化 ttl_minutes，续约路径
--   （renewLease / adaptiveRenew）每次都重新推断窗口，调用方
--   checkIn{ttlMinutes:60} 的窗口无处保存；在飞子任务续约时被无条件
--   放大到 maxTtlMinutes(240)，且空闲时又回落到按表现分推断的动态值，
--   导致心跳回传的 remainingTtlSeconds 反复跳变（实测 14399 → 3359），
--   与 checkIn 承诺的 expiresAt 口径不一致。
--   本迁移持久化签发时解析出的窗口，使租约窗口「可查、稳定、可复用」。
-- 口径：
--   NULL 表示历史行未持久化（空闲续约回退既有动态推断，行为与迁移前一致）；
--   非 NULL 表示签发时解析出的窗口，空闲续约复用同一值，不再按表现分重算。
--   续约路径（renewLease）**不改写**本列：在飞保活用的 maxTtlMinutes(240) 只体现在
--   expire_time 上，不污染签发窗口。
-- 幂等：ADD COLUMN IF NOT EXISTS。
-- ============================================================

ALTER TABLE agent_duty_lease
    ADD COLUMN IF NOT EXISTS ttl_minutes INT;

COMMENT ON COLUMN agent_duty_lease.ttl_minutes IS '本次签发/续约采用的租约窗口（分钟），续约复用该值；NULL 表示未持久化、按配置动态推断';
