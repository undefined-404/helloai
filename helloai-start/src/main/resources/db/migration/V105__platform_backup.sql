-- ============================================================
-- V105__platform_backup.sql
-- 用途：平台备份台账（REF-2.3）+ 管理端权限码
--
-- 背景（`D-2026-10-10-2`）：
--   REF-2 起平台具备「数据库全库 + 对象存储对象」的备份能力。备份产物落
--   **独立 bucket `helloai-backups`**（**不参与**对账巡检 —— `ArtifactStorageReconcileServiceImpl`
--   以 `listObjects(bucket, null)` 枚举整桶，凡无 DB 记录者即孤儿候选；备份若落
--   `helloai-artifacts`，一旦开 `orphan-cleanup-enabled` 就会被当孤儿删掉）。
--   本表是备份的**台账**：回答「有哪些备份、谁触发的、成功没有、多大」——否则这些
--   事实只存在于对象存储里，无从查询。
--
-- 保留策略（`D-2026-10-10-2⑤`）：
--   仅对 `backup_type='AUTO'` 按份数淘汰（默认 7）；`MANUAL` **永不淘汰** ——
--   由应用层保证，本表只记录类型。
--
-- 关键设计：
--   - BaseEntity 通用审计列，映射 BaseEntity（与 credential_audit_log / agent_event 同款）
--   - `update_time` 走 V1 的 `update_update_time_column()` 触发器（state 会被更新，故需要）
--   - 一次备份 = 桶下一个**前缀**（`{type}/{yyyy}/{MM}/{dd}/{uuid8}/`），
--     prefix 内至少含 manifest + dump；对象清单写进 manifest
--   - 恢复侧三门（`D-2026-10-10-2⑦`）依赖 pg_version 与 flyway_max_version 两列，
--     它们由备份时从归档头与 flyway_schema_history 读出后落库
--
-- 权限：`backup:view` / `backup:run`（id 127 / 128，接续 V104 的 126）
-- 幂等：CREATE TABLE IF NOT EXISTS + CREATE INDEX IF NOT EXISTS + ON CONFLICT DO NOTHING
-- 红线：V1~V104 只读，本迁移递增为 V105（Flyway 校验和不可变，CODE_STYLE §16）
-- 回滚：DROP TABLE platform_backup；按 code 反向删 sys_role_permission / sys_permission
-- ============================================================

CREATE TABLE IF NOT EXISTS platform_backup (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    backup_type        VARCHAR(16)  NOT NULL,
    state              VARCHAR(16)  NOT NULL,
    object_prefix      VARCHAR(512),
    manifest_key       VARCHAR(512),
    dump_key           VARCHAR(512),
    pg_version         VARCHAR(32),
    flyway_max_version VARCHAR(32),
    artifact_count     INTEGER,
    artifact_bytes     BIGINT,
    dump_bytes         BIGINT,
    total_bytes        BIGINT,
    checksum_sha256    VARCHAR(64),
    failure_reason     VARCHAR(512),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    create_by          VARCHAR(64)  NOT NULL DEFAULT '',
    update_by          VARCHAR(64)  NOT NULL DEFAULT '',
    create_time        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            SMALLINT     NOT NULL DEFAULT 0,
    remark             VARCHAR(255),
    CONSTRAINT chk_platform_backup_type  CHECK (backup_type IN ('MANUAL', 'AUTO')),
    CONSTRAINT chk_platform_backup_state CHECK (state IN ('RUNNING', 'SUCCESS', 'FAILED'))
);

-- 保留淘汰：按类型 + 时间倒序取"该淘汰的旧自动备份"
CREATE INDEX IF NOT EXISTS idx_platform_backup_type_started
    ON platform_backup (backup_type, started_at DESC) WHERE deleted = 0;
-- 列表/详情：按时间倒序
CREATE INDEX IF NOT EXISTS idx_platform_backup_started
    ON platform_backup (started_at DESC) WHERE deleted = 0;
-- 在飞备份探测（在线恢复拒的第一个判据之一）
CREATE INDEX IF NOT EXISTS idx_platform_backup_state
    ON platform_backup (state) WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_platform_backup_update_time ON platform_backup;
CREATE TRIGGER update_platform_backup_update_time BEFORE UPDATE ON platform_backup
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE  platform_backup IS '平台备份台账（REF-2.3）：数据库全库 + 对象存储对象的备份记录';
COMMENT ON COLUMN platform_backup.backup_type IS 'MANUAL=手动触发（永不淘汰）/ AUTO=定时自动（参与按份数保留淘汰）';
COMMENT ON COLUMN platform_backup.state IS 'RUNNING=进行中 / SUCCESS=成功 / FAILED=失败（失败时 failure_reason 给出可读原因）';
COMMENT ON COLUMN platform_backup.object_prefix IS '本次备份在 helloai-backups 桶下的前缀（一次备份一个前缀，内部含 manifest 与 dump）';
COMMENT ON COLUMN platform_backup.manifest_key IS 'manifest 对象键 —— 恢复侧三门先读它（不解档）';
COMMENT ON COLUMN platform_backup.dump_key IS 'pg_dump -Fc 归档对象键';
COMMENT ON COLUMN platform_backup.pg_version IS '归档头 "Dumped from database version"（跨引擎拒的判据）';
COMMENT ON COLUMN platform_backup.flyway_max_version IS '备份时的 flyway_schema_history 最高版本（schema 高过运行时拒的判据）';
COMMENT ON COLUMN platform_backup.artifact_count IS '纳入本次备份的对象存储对象数（listObjects 枚举结果）';
COMMENT ON COLUMN platform_backup.checksum_sha256 IS 'manifest 的 SHA-256（备份整体摘要）';

-- ------------------------------------------------------------
-- 权限码（id 127 / 128，接续 V104 的 126）
-- 绑定口径同 V103/V104：ADMIN(2) 绑；NORMAL_USER(3) 不绑 —— 备份属管理动作。
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (127, 'backup:view', '备份-查看',   'API', 119, 'system', 'system'),
    (128, 'backup:run',  '备份-手动触发', 'API', 120, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN ('backup:view', 'backup:run')
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 验证日志
-- ------------------------------------------------------------
DO $$
DECLARE
    t_cnt    INTEGER;
    perm_cnt INTEGER;
BEGIN
    SELECT COUNT(*) INTO t_cnt
    FROM information_schema.tables WHERE table_name = 'platform_backup';

    SELECT COUNT(*) INTO perm_cnt
    FROM sys_permission
    WHERE deleted = 0 AND code IN ('backup:view', 'backup:run');

    RAISE NOTICE '[V105] platform_backup 表=% / 新权限码行数=%', t_cnt, perm_cnt;
END $$;
