-- ============================================================
-- 平台配置动态化：credential_vault 放开 PLATFORM 级 owner_type
-- ============================================================
-- 背景：平台级 LLM Provider API Key 由管理员在"系统设置"页填写/轮换，
--       写入 credential_vault（owner_type=PLATFORM、owner_id 固定占位 0、
--       按 provider 唯一），替代 application.yml 启动期一次性绑定。
-- 兼容性：V1 与 V14 均定义同名约束 chk_credential_vault_owner_type
--         （V14 IF NOT EXISTS 建表时约束已存在则跳过），约束名一致，
--         按实际存在性 DROP + ADD，保证任意历史库可重复执行。
-- Agent 级凭证（owner_type=AGENT）不受影响。
ALTER TABLE credential_vault DROP CONSTRAINT IF EXISTS chk_credential_vault_owner_type;
ALTER TABLE credential_vault ADD CONSTRAINT chk_credential_vault_owner_type
    CHECK (owner_type IN ('AGENT', 'PLATFORM'));

COMMENT ON COLUMN credential_vault.owner_type IS '归属对象类型：AGENT=Agent 级凭证（owner_id=agent.id）；PLATFORM=平台级凭证（owner_id 固定占位 0，按 provider 唯一）';
