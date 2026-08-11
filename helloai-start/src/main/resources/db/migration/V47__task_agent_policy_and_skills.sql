-- ============================================================
-- V47__task_agent_policy_and_skills.sql
-- 用途：任务级 Agent 指定策略 + 能力声明（§6.58 P1）
-- 背景：
--   目标态（架构参考 §4.8 目标态八）：外部 Agent 优先 + API_KEY_LLM 同角色保底，
--   并要求"Agent 能力满足当前子任务要求"。此前选人完全由平台策略（score/值班/
--   preferExternal）决定，任务发起方无法指定执行/核验 Agent，也无从约束能力匹配。
-- 机制：
--   - task.agent_policy（JSONB）：任务级 Agent 指定策略
--     - plannerAgentId：指定拆解/澄清 Planner（失效回退自动选择）
--     - executorAgentIds[]：执行者白名单（为空=不限定；非空=只允许集合内 Agent）
--     - reviewerAgentId：指定自动核验 Reviewer（失效回退自动选择）
--     - fallbackPolicy：AUTO（默认，N11 正常回退）/ RESTRICTED（仅回退集合内
--       API_KEY_LLM）/ NONE（禁止 N11 自动回退，打人工介入）
--     - difficulty：LOW / MEDIUM（默认）/ HIGH（HIGH 视为禁止 N11 自动回退）
--   - task.required_skills（JSONB[]）：任务要求的能力列表；非空时执行者必须
--     全部具备（AND 语义），弥补 isExecutionDense 启发式误判（§6.56 遗留③）
--   - agent.skills（JSONB[]）：Agent 声明的能力列表；默认空（旧数据不受影响，
--     但 required_skills 非空的任务将无法选中未声明能力的 Agent）
--   - 默认 '{}'/'[]'：旧数据行为与现状完全一致
-- ============================================================

ALTER TABLE task
    ADD COLUMN IF NOT EXISTS agent_policy JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE task
    ADD COLUMN IF NOT EXISTS required_skills JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE agent
    ADD COLUMN IF NOT EXISTS skills JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN task.agent_policy IS '任务级 Agent 指定策略（JSONB，V47 新增。plannerAgentId/executorAgentIds[]/reviewerAgentId/fallbackPolicy(AUTO|RESTRICTED|NONE)/difficulty(LOW|MEDIUM|HIGH)，默认 {} 不限制）';
COMMENT ON COLUMN task.required_skills IS '任务要求的能力列表（JSONB[]，V47 新增。非空时执行者必须全部具备，AND 语义；默认 [] 不限制）';
COMMENT ON COLUMN agent.skills IS 'Agent 声明的能力列表（JSONB[]，V47 新增。注册时按接入方式声明，供任务 required_skills 匹配；默认 []）';

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V47] task.agent_policy / task.required_skills / agent.skills 列已就绪';
END $$;
