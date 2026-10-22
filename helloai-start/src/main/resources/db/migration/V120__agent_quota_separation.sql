-- V120__agent_quota_separation.sql
-- 目的：N11 外部 Agent 阈值回退的「单一连续失败计数」拆分为三配额（Paperclip 阶段一·4）。
--
-- 背景：`consecutive_failure_count` 一个计数承载了所有失败类型 —— 离线失联（recovery）、
--   执行超时（recovery）、空回复（continuation 可容忍）混在一起，空回复会挤占真失败配额，
--   导致「没真正坏掉的 Agent 被提前回退」。拆分后三类失败各自计数、互不挤占。
--
-- 列口径（与 core FailureCategory 枚举一一对应）：
--   · retry_count         —— 显式 retry 指令失败（本期无写入源，列先就位）
--   · continuation_count  —— 空回复/续跑类失败（EMPTY_REPLY，可容忍）
--   · recovery_count      —— 真失败：离线失联 / 执行超时 / 执行报错（严格配额）
--
-- 存量语义：原 consecutive_failure_count 作为「总览」字段保留（先改语义后动列，
--   见 plan §1.4 改动方案 3）；存量行按「全归 recovery」初始化（保守口径：历史失败
--   都视为真失败，避免放松已有阈值保护）。
--
-- 回滚：DROP COLUMN 三列即可（consecutive_failure_count 未被改动）。

ALTER TABLE agent
    ADD COLUMN retry_count        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN continuation_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN recovery_count     INTEGER NOT NULL DEFAULT 0;

-- 存量数据：历史失败全归 recovery（保守口径，不放松已有阈值保护）
UPDATE agent
SET recovery_count = COALESCE(consecutive_failure_count, 0)
WHERE deleted = 0;
