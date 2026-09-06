-- ============================================================
-- V67__create_credential_audit_log.sql
-- 用途：凭证操作审计台账（Phase 2 B2，N-004 Credential Vault 收口）
-- 背景：
--   差距表 N-004 完成标准「异常情况下如何恢复」无审计支撑——
--   旧实现仅在 remark 累积 rotated_from_id，无独立可查台账。
--   V67 提供 append-only 审计表：bind / rotate / revoke / expire
--   四类凭证操作落审计，供安全审计与恢复取证。
-- 关键设计：
--   - BaseEntity 通用审计列（create_by/update_by/create_time/update_time/deleted/remark）
--     与 agent_event / agent_outbox_event 同款，映射 BaseEntity
--   - action 存 snake_case（bind/rotate/revoke/expire，CredentialAuditAction 常量）
--   - detail 记录关键事实（如 rotated_from_id / 过期前状态 / expiresAt）
--   - 与 agent_event 相同：只 INSERT，不 UPDATE 业务列（审计保留原始记录）
-- 参考：doc/design/HelloAI_Phase2_B2_CredentialVault收口执行方案.md §3.1
-- ============================================================

CREATE TABLE IF NOT EXISTS credential_audit_log (
    id              BIGINT      NOT NULL PRIMARY KEY,
    credential_id   BIGINT,
    owner_type      VARCHAR(32),
    owner_id        BIGINT,
    provider        VARCHAR(64),
    action          VARCHAR(32) NOT NULL,
    operator        VARCHAR(64) NOT NULL DEFAULT '',
    detail          VARCHAR(1000),
    create_by       VARCHAR(64) NOT NULL DEFAULT '',
    update_by       VARCHAR(64) NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_credential_audit_log_credential
    ON credential_audit_log(credential_id, create_time);
CREATE INDEX IF NOT EXISTS idx_credential_audit_log_owner
    ON credential_audit_log(owner_type, owner_id, create_time);
DROP TRIGGER IF EXISTS update_credential_audit_log_update_time ON credential_audit_log;
CREATE TRIGGER update_credential_audit_log_update_time BEFORE UPDATE ON credential_audit_log
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE credential_audit_log IS '凭证操作审计台账（Phase 2 B2，append-only）';
COMMENT ON COLUMN credential_audit_log.credential_id IS '被操作凭证 ID（credential_vault.id，可能为 NULL——审计与凭证同事务，操作失败整体回滚）';
COMMENT ON COLUMN credential_audit_log.owner_type IS '归属对象类型：AGENT / PLATFORM';
COMMENT ON COLUMN credential_audit_log.owner_id IS '归属对象 ID（PLATFORM 固定 0）';
COMMENT ON COLUMN credential_audit_log.provider IS 'LLM Provider 标识';
COMMENT ON COLUMN credential_audit_log.action IS '动作：bind / rotate / revoke / expire';
COMMENT ON COLUMN credential_audit_log.operator IS '操作者：admin（管理端）/ system（定时任务）';
COMMENT ON COLUMN credential_audit_log.detail IS '关键事实明细（如 rotated_from_id / 过期前状态）';
