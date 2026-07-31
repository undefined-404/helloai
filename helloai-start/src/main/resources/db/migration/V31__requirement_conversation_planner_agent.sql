-- V31: requirement_conversation 增加 planner_agent_id
-- 对话新建（需求澄清）支持手动指定 Planner Agent；NULL 表示系统自动选择。
-- 软引用无 FK（与 task_id 同款约定）：Agent 删除后允许悬挂，使用时回退自动选择。

ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS planner_agent_id BIGINT;

COMMENT ON COLUMN requirement_conversation.planner_agent_id
    IS '手动指定的 Planner Agent ID（软引用无 FK；NULL=系统自动选择；澄清与后续拆解均跟随该 Planner）';
