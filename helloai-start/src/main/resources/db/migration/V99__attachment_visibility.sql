-- V99__attachment_visibility.sql
-- 附件可见性（授权作用域）：PERSONAL / TASK / PUBLIC
-- 设计依据：doc/design/HelloAI Task-Team 与附件可见性设计.md §4
--
-- 为什么不加 attachment.task_id：
--   可见性不绑死到具体任务 / 子任务，而由「声明的范围（本列）× 请求者的成员关系」共同判定。
--   判定入口单一化为 AttachmentAccessPolicy.canRead(agentId, att)，内部只引用 rootTaskIdOf(att) 抽象
--   ⇒ 未来「二次分解（子任务的子任务）」「一个需求拆多个主任务」等层级演进，只需扩展 rootTaskIdOf，
--     权限判定入口零改动。**故本列是"范围声明"，不是"归属外键"。**
--
-- 回填说明（为什么不需要清库）：
--   PostgreSQL 11+ 对「ADD COLUMN + 常量 DEFAULT」是纯元数据操作（不重写表、不逐行 UPDATE），
--   存量行自动读作 DEFAULT 值。本机 PG 16.4 ⇒ 93 个历史附件一步到位读作 'TASK'，
--   **无需显式 UPDATE、无需清库、无需重建任务**。

ALTER TABLE attachment ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'TASK';

COMMENT ON COLUMN attachment.visibility IS '附件可见范围：PERSONAL=仅上传者（离队后仍可读自传）；TASK=上传者 + 所属根任务的 task_agent_member 成员（默认，本次改造目的）；PUBLIC=所有 ACTIVE agent（预留，不开放设置入口）';

-- CHECK 用 DO 块包裹保证幂等（ADD CONSTRAINT 本身不幂等；Flyway 虽只跑一次，仍防 repair/手工重放）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_attachment_visibility'
    ) THEN
        ALTER TABLE attachment ADD CONSTRAINT chk_attachment_visibility
            CHECK (visibility IN ('PERSONAL', 'TASK', 'PUBLIC'));
    END IF;
END $$;
