-- ============================================================
-- V138: 撤销会话置顶列（PA-11 v2 回退）
--
-- 背景：V137 加的 pinned 列服务于工作台输入栏「钉住」按钮。
--   该按钮实为设计稿中的「上下文压缩」按钮（读图误判），置顶能力本身不需要，
--   故按用户裁定整条置顶能力下线：前端按钮 / API 端点 / 实体字段 / 列表排序全部移除。
--
-- 口径：布尔列按 CODE_STYLE §9.3 用 SMALLINT(0/1)；撤销时先删索引再删列。
--
-- 红线：V1~V137 只读；本迁移只删 V137 新增的列与索引，不触碰其它列与存量数据。
-- 回滚：ALTER TABLE requirement_conversation ADD COLUMN pinned SMALLINT NOT NULL DEFAULT 0;
-- ============================================================

DROP INDEX IF EXISTS idx_requirement_conversation_pinned;

ALTER TABLE requirement_conversation DROP COLUMN IF EXISTS pinned;
