-- N-004 凭证生命周期收口：老库（V14 建表）的 chk_credential_vault_status 仅允许
-- ACTIVE/DISABLED，与 V1 全量基线（含 EXPIRED）漂移。轮换（rotateApiKey）会把旧凭证
-- 置为 EXPIRED，过期扫描（expireOverdue）也会写 EXPIRED——缺此迁移时保存新 Key 报
-- "chk_credential_vault_status" 约束违反（新行 status=EXPIRED）。
ALTER TABLE credential_vault DROP CONSTRAINT IF EXISTS chk_credential_vault_status;
ALTER TABLE credential_vault ADD CONSTRAINT chk_credential_vault_status
    CHECK (status IN ('ACTIVE', 'DISABLED', 'EXPIRED'));