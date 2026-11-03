-- ============================================================
-- V137: 会话置顶（PA-11 v2 · 工作台输入栏「钉住」）
--
-- 用途：工作台输入栏按设计图提供「钉住」按钮——把当前会话置顶到历史列表最前，
--   避免常用会话被时间倒序挤到列表深处。助理会话与需求会话共用 requirement_conversation，
--   故本列对两类会话通用。
--
-- 口径：布尔列按 CODE_STYLE §9.3 用 SMALLINT(0/1) 而非 BOOLEAN；
--   实体侧用 SmallIntBooleanTypeHandler 映射（写入 0/1，读取 0→false / 1→true）。
--
-- 红线：V1~V136 只读；仅加列，不改既有数据（存量行默认 0 = 未置顶，行为不变）。
-- 回滚：ALTER TABLE requirement_conversation DROP COLUMN pinned;
-- ============================================================

ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS pinned SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN requirement_conversation.pinned IS
    '会话置顶（PA-11 v2）：1=置顶（历史列表优先展示），0=未置顶';

-- 历史列表排序：置顶优先，其次创建时间倒序
CREATE INDEX IF NOT EXISTS idx_requirement_conversation_pinned
    ON requirement_conversation (pinned, create_time DESC)
    WHERE deleted = 0;
