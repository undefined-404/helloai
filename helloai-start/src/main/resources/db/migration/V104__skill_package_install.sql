-- ============================================================
-- V104__skill_package_install.sql
-- 用途：技能包安装入口的持久化（REF-1.6）——元数据 + 原始正文落 PG，原始上传 zip 落 MinIO
--
-- 背景（2026-10-10，`D-2026-10-10-1`）：
--   安装入口此前不存在（技能包元数据事实源 = classpath `skills/plugins/*.md`，REF-1.1/1.2）。
--   本迁移新增「已安装技能包」受控存储面，与 classpath 内置源**双源并存**：
--     - PG 存元数据 + **原始正文**（`body`）——`resolve()` 在每轮装配热路径读技能正文，
--       正文若只在 MinIO，则 MinIO 故障会让技能注入整链断（同 `D-2026-10-10-1②`）；
--     - MinIO 存**原始上传 zip**（`origin_zip_url`），用于溯源 / 完整性 / 再分发。
--   **不走宿主文件系统** ⇒ 不构成 `G-005` 触发条件①「平台增加碰宿主的工具」，
--   REF-3 沙箱维持「条件触发，不排期」。
--
-- 版本策略（`D-2026-10-10-1⑦⑧`，应用层实现，本迁移只给结构）：
--   - 唯一键 `(name, version)`：**多版本共存**（回滚需要历史行）；
--   - 同 name **至多一行 `state='ACTIVE'`**（partial unique index 保证）；
--   - 安装：同名更高版本 ⇒ 需确认；更低或相同 ⇒ 拒绝；
--   - 回滚 / 降版走 `POST /api/skills/packages/{name}/activate`；
--   - 与内置 `eng-*` 同名一律拒绝安装（应用层判断，不在 DB 约束）。
--
-- 权限：`skill:install` / `skill:uninstall`（id 125 / 126，接续 V103 的 124）
-- 幂等：CREATE TABLE IF NOT EXISTS + CREATE INDEX IF NOT EXISTS + ON CONFLICT DO NOTHING
-- 红线：V1~V103 只读，本迁移递增为 V104（Flyway 校验和不可变，CODE_STYLE §16）
-- 回滚：DROP TABLE skill_package_audit / skill_package；按 code 反向删 sys_role_permission / sys_permission
-- ============================================================

-- ------------------------------------------------------------
-- 1) 技能包（已安装）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS skill_package (
    id               BIGINT       NOT NULL PRIMARY KEY,
    name             VARCHAR(64)  NOT NULL,
    version          VARCHAR(32)  NOT NULL,
    description      VARCHAR(512) NOT NULL DEFAULT '',
    required_tools   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    dependencies     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    input_schema     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    output_schema    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    validation_rules JSONB        NOT NULL DEFAULT '[]'::jsonb,
    body             TEXT         NOT NULL,
    manifest_name    VARCHAR(128) NOT NULL,
    origin           VARCHAR(16)  NOT NULL DEFAULT 'INSTALLED',
    locked           SMALLINT     NOT NULL DEFAULT 0,
    state            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    checksum_sha256  VARCHAR(64)  NOT NULL,
    origin_zip_url   VARCHAR(512),
    create_by        VARCHAR(64)  NOT NULL DEFAULT '',
    update_by        VARCHAR(64)  NOT NULL DEFAULT '',
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT     NOT NULL DEFAULT 0,
    remark           VARCHAR(255),
    CONSTRAINT chk_skill_package_state  CHECK (state IN ('ACTIVE', 'HISTORICAL', 'DISABLED')),
    CONSTRAINT chk_skill_package_origin CHECK (origin IN ('BUILTIN', 'INSTALLED'))
);

-- 多版本共存：同 (name, version) 唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_package_name_version
    ON skill_package (name, version) WHERE deleted = 0;

-- 同 name 至多一行 ACTIVE（「当前生效哪一版」恒为单值，回滚只是翻状态）
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_package_active_name
    ON skill_package (name) WHERE deleted = 0 AND state = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_skill_package_name
    ON skill_package (name) WHERE deleted = 0;

COMMENT ON TABLE  skill_package IS '已安装技能包（REF-1.6）：元数据 + 原始正文落 PG，原始 zip 落 MinIO；与 classpath 内置源双源并存';
COMMENT ON COLUMN skill_package.body IS '技能包原始正文（含 frontmatter），非预渲染速览——渲染器升级后存量包自动重算（D-2026-10-10-1⑩）';
COMMENT ON COLUMN skill_package.manifest_name IS '包内清单文件名（固定 skill-package-manifest.md），记录本包是按哪个清单校验通过的';
COMMENT ON COLUMN skill_package.origin IS 'BUILTIN=随发版内置（当前不落库，预留）/ INSTALLED=经安装入口装入';
COMMENT ON COLUMN skill_package.locked IS 'REF-1.4 来源锁定标记：1=禁止下游改写（打戳能力见 REF-1.4）';
COMMENT ON COLUMN skill_package.state IS 'ACTIVE=当前生效（同 name 唯一）/ HISTORICAL=历史版本（可 activate 回滚）/ DISABLED=已停用';
COMMENT ON COLUMN skill_package.checksum_sha256 IS '原始上传 zip 的 SHA-256（完整性 / 溯源）';
COMMENT ON COLUMN skill_package.origin_zip_url IS '原始上传 zip 在 ArtifactStorage 中的地址（minio:// 或 local://）';

-- ------------------------------------------------------------
-- 2) 安装审计
--    理由：安装是**供应链入口**（新的对外输入面），比普通管理端 CRUD 重，
--    对齐凭证域 CredentialAuditLog 先例（D-2026-10-10-1⑥）。
--    操作人**不能**依赖 MyBatisPlusMetaObjectHandler —— 其 getCurrentUser() 是硬编码桩、
--    恒返 "system"，全平台 create_by 不记操作人（§7.1.3 R5）；故本表 operator_* 由应用显式写入。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS skill_package_audit (
    id              BIGINT       NOT NULL PRIMARY KEY,
    package_id      BIGINT,
    name            VARCHAR(64)  NOT NULL,
    version         VARCHAR(32)  NOT NULL DEFAULT '',
    action          VARCHAR(16)  NOT NULL,
    operator_id     VARCHAR(64)  NOT NULL DEFAULT '',
    operator_name   VARCHAR(128) NOT NULL DEFAULT '',
    result          VARCHAR(16)  NOT NULL,
    reason          VARCHAR(512),
    checksum_sha256 VARCHAR(64),
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255),
    CONSTRAINT chk_skill_package_audit_action CHECK (action IN ('INSTALL', 'ACTIVATE', 'UNINSTALL', 'REJECT')),
    CONSTRAINT chk_skill_package_audit_result CHECK (result IN ('SUCCESS', 'FAIL'))
);

CREATE INDEX IF NOT EXISTS idx_skill_package_audit_name
    ON skill_package_audit (name, create_time DESC) WHERE deleted = 0;

COMMENT ON TABLE  skill_package_audit IS '技能包安装 / 激活 / 卸载 / 拒绝审计（REF-1.6）；操作人取自 Sa-Token 会话，不依赖 MetaObjectHandler';
COMMENT ON COLUMN skill_package_audit.package_id IS '关联 skill_package.id；允许为空（安装被拒时无包行）。刻意不建 FK——审计须在包删除后存活';
COMMENT ON COLUMN skill_package_audit.action IS 'INSTALL=安装 / ACTIVATE=激活（回滚或降版）/ UNINSTALL=卸载 / REJECT=被闸门或版本策略拒绝';
COMMENT ON COLUMN skill_package_audit.result IS 'SUCCESS / FAIL';

-- ------------------------------------------------------------
-- 3) 权限码（id 125 / 126，接续 V103 的 124）
--    绑定口径同 V103：ADMIN(2) 绑；NORMAL_USER(3) 不绑——安装 / 卸载属管理动作。
--    降版 / 回滚复用 skill:install（同一个动作面，不新增权限码）。
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (125, 'skill:install',   '技能包-安装/激活', 'API', 117, 'system', 'system'),
    (126, 'skill:uninstall', '技能包-卸载',      'API', 118, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN ('skill:install', 'skill:uninstall')
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 4) 验证日志（启动时输出建表与权限播种结果）
-- ------------------------------------------------------------
DO $$
DECLARE
    t_pkg    INTEGER;
    t_audit  INTEGER;
    perm_cnt INTEGER;
BEGIN
    SELECT COUNT(*) INTO t_pkg
    FROM information_schema.tables WHERE table_name = 'skill_package';

    SELECT COUNT(*) INTO t_audit
    FROM information_schema.tables WHERE table_name = 'skill_package_audit';

    SELECT COUNT(*) INTO perm_cnt
    FROM sys_permission
    WHERE deleted = 0 AND code IN ('skill:install', 'skill:uninstall');

    RAISE NOTICE '[V104] skill_package 表=% / skill_package_audit 表=% / 新权限码行数=%',
        t_pkg, t_audit, perm_cnt;
END $$;
