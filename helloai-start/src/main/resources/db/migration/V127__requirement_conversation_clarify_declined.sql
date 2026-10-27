-- V127__requirement_conversation_clarify_declined.sql
-- 目的：确认卡「取消」后的拒绝记忆（2026-10-27 修复死循环）。
--
-- 背景：CHAT 模式 LLM 意图路由判 clarify 时弹确认卡（pending_clarify_confirm=1）。
--   用户点「取消」后仅清该标记，没有任何「用户已拒绝转入澄清」的记忆；
--   下一条消息若又被 LLM 判为 clarify（历史含确认卡 + 「整理成方案」字样极易复判），
--   会再次弹确认卡 → 死循环。本列在取消时置 1，作为「拒绝冷却」记忆：
--   决策阶段若本列为 1，即使 LLM 判 clarify 也不弹卡、按普通对话回复。
--
-- 语义：
--   NULL/0 = 未拒绝（默认）。LLM 判 clarify 可弹确认卡（既有行为）。
--   1     = 用户最近一次拒绝了转入澄清（点「取消」或回复非确认内容）。
--          决策阶段见 clarify_declined=1 时抑制确认卡，保持 CHAT 对话；
--          用户显式输入 /planner /task 或明确表达「做方案」意图（连续多次）可重置。
--
-- 重置时机（代码侧）：switchToClarify（显式命令切换）置 0；
--   sendMessage 中若用户明确表达再次转方案（非确认卡、LLM 判 clarify 且无卡可弹）置 0。
--
-- 回滚：ALTER TABLE requirement_conversation DROP COLUMN clarify_declined;

ALTER TABLE requirement_conversation
    ADD COLUMN clarify_declined SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN requirement_conversation.clarify_declined IS
    '确认卡拒绝记忆：1=用户最近拒绝了转入方案澄清（抑制后续确认卡弹窗，防死循环）；'
    '0/NULL=未拒绝。显式 /planner 命令或明确再次转方案时重置。';
