-- 允许历史版本保留，但同一 owner/provider/type 只能有一条 ACTIVE（deleted=0）。
-- 迁移目标：
-- 1) 清理存量重复 ACTIVE（保留最新一条 ACTIVE，其余置为 DISABLED）
-- 2) 建立部分唯一索引约束，防止再出现多条 ACTIVE

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY owner_type, owner_id, provider, credential_type
               ORDER BY create_time DESC, id DESC
           ) AS rn
    FROM credential_vault
    WHERE deleted = 0 AND status = 'ACTIVE'
)
UPDATE credential_vault v
SET status = 'DISABLED',
    update_time = CURRENT_TIMESTAMP
FROM ranked r
WHERE v.id = r.id AND r.rn > 1;

DROP INDEX IF EXISTS uk_credential_vault_owner_provider_type;

CREATE UNIQUE INDEX IF NOT EXISTS uk_credential_vault_active_owner_provider_type
    ON credential_vault(owner_type, owner_id, provider, credential_type)
    WHERE deleted = 0 AND status = 'ACTIVE';

