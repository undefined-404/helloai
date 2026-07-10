CREATE TABLE IF NOT EXISTS credential_vault (
    id               BIGINT NOT NULL PRIMARY KEY,
    owner_type       VARCHAR(32)  NOT NULL DEFAULT 'AGENT',
    owner_id         BIGINT       NOT NULL,
    provider         VARCHAR(64)  NOT NULL,
    credential_type  VARCHAR(32)  NOT NULL DEFAULT 'API_KEY',
    encrypted_value  TEXT,
    secret_ref       VARCHAR(255),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    expires_at       TIMESTAMPTZ,
    create_by        VARCHAR(64)  NOT NULL DEFAULT '',
    update_by        VARCHAR(64)  NOT NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT     NOT NULL DEFAULT 0,
    remark           VARCHAR(255),
    CONSTRAINT chk_credential_vault_owner_type CHECK (owner_type IN ('AGENT')),
    CONSTRAINT chk_credential_vault_credential_type CHECK (credential_type IN ('API_KEY')),
    CONSTRAINT chk_credential_vault_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_credential_vault_value CHECK (encrypted_value IS NOT NULL OR secret_ref IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_credential_vault_owner ON credential_vault(owner_type, owner_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_credential_vault_status ON credential_vault(status) WHERE deleted = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_credential_vault_owner_provider_type
    ON credential_vault(owner_type, owner_id, provider, credential_type) WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_credential_vault_update_time ON credential_vault;
CREATE TRIGGER update_credential_vault_update_time BEFORE UPDATE ON credential_vault
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE credential_vault IS '凭证保险库（真实 LLM 凭证只存这里，agent.api_key 只保留工牌语义）';
COMMENT ON COLUMN credential_vault.owner_type IS '归属对象类型：当前固定为 AGENT';
COMMENT ON COLUMN credential_vault.owner_id IS '归属对象 ID，例如 agent.id';
COMMENT ON COLUMN credential_vault.provider IS 'LLM Provider 标识，如 deepseek/openai/claude';
COMMENT ON COLUMN credential_vault.credential_type IS '凭证类型：当前固定为 API_KEY';
COMMENT ON COLUMN credential_vault.encrypted_value IS '应用层加密后的凭证值';
COMMENT ON COLUMN credential_vault.secret_ref IS '外部 Secret 引用（与 encrypted_value 二选一或并存）';
COMMENT ON COLUMN credential_vault.status IS '凭证状态：ACTIVE/DISABLED';
COMMENT ON COLUMN credential_vault.expires_at IS '可选到期时间';
