-- 个人工作台「选模型」（2026-11-05）
--
-- 背景：工作台 Composer 原先复用 planner 下拉（listPlannerOptions），把「角色」
-- 和「模型」混在一个候选集里；用户要的是「选一个已注册且有效的纯 LLM 模型」。
-- 会话因此需要记住本轮钉住的模型，与既有 planner_agent_id（角色）并存：
--
--   planner_agent_id  = 谁来「扮演」助理（PLANNER 角色，决定提示词/技能）
--   model_type        = 用哪个模型答（provider:model，形如 deepseek:deepseek-v4-flash）
--
-- NULL = 老数据与「未指定」兼容，执行链回落到 Planner Agent 自身绑定的模型，
-- 行为与本次改造前逐字一致（不改变任何既有会话的执行结果）。
--
-- 列长 160：与 agent.model_type 同口径（provider + ':' + model），留足自定义模型名余量。

ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS model_type VARCHAR(160);

COMMENT ON COLUMN requirement_conversation.model_type IS
    '会话钉住的 LLM 模型（provider:model）；NULL=沿用 Planner Agent 绑定模型（老数据兼容）';
