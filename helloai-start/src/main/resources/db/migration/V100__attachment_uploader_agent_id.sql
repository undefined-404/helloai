-- V100__attachment_uploader_agent_id.sql
-- 附件上传者 Agent ID：支撑 visibility=PERSONAL（仅上传者）语义
-- 设计依据：doc/design/HelloAI Task-Team 与附件可见性设计.md §4.1
--
-- 为什么必须补这一列：
--   attachment 原有的 create_by/update_by 是**审计列**，由平台统一填充（非上传者 agent 身份），
--   无法表达"这份附件是谁上传的"。而：
--     ① visibility=PERSONAL 的语义就是「仅上传者本人可读」；
--     ② visibility=TASK 分支也需要"上传者恒可读自传"兜底（agent 被改派换下后仍应能读自己的旧产出）。
--   故新增本列作为**上传者身份**的事实来源，由 AttachmentService#register 显式写入。
--
-- 回填：上传附件时平台强制校验 agentId == sub_task.assigned_agent_id
--   （见 AttachmentServiceImpl#register），故存量附件的上传者 = 其所属子任务当前执行者。

ALTER TABLE attachment ADD COLUMN IF NOT EXISTS uploader_agent_id BIGINT;

COMMENT ON COLUMN attachment.uploader_agent_id IS '上传者 Agent ID：由 AttachmentService#register 写入；平台侧/人工上传为空。用于 visibility=PERSONAL（仅上传者）与 TASK 分支的"上传者恒可读自传"兜底';

-- 存量回填：上传者 = 附件所属子任务的当前执行者（register 的既有强校验保证成立）
UPDATE attachment a
SET uploader_agent_id = s.assigned_agent_id
FROM sub_task s
WHERE s.id = a.sub_task_id
  AND a.uploader_agent_id IS NULL
  AND s.assigned_agent_id IS NOT NULL;

-- 可见性判据热路径：按上传者反查（PERSONAL 分支）
CREATE INDEX IF NOT EXISTS idx_attachment_uploader
    ON attachment(uploader_agent_id) WHERE deleted = 0 AND uploader_agent_id IS NOT NULL;
