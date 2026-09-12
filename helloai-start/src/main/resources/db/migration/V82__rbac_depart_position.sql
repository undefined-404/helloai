-- ============================================================
-- RBAC 基础架构深化（BASE-3.2）：部门 / 岗位组织架构
-- 背景：对齐 JeecgBoot 企业级 RBAC——补组织架构底座：
--       ① sys_depart 部门树（parent_id 承载层级）+ sys_user_depart 用户-部门多对多；
--       ② sys_position 岗位 + sys_user_position 用户-岗位多对多；
--       ③ 菜单项（系统设置下「部门管理」「岗位管理」）+ 动作级权限码（仅 SUPER_ADMIN）。
-- 影响：V77~V81 只读，本迁移 V82 递增；纯新增表/种子，存量数据行为不变。
-- ============================================================

-- ------------------------------------------------------------
-- 1. sys_depart 部门表（树形）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_depart (
    id          BIGINT       NOT NULL PRIMARY KEY,
    parent_id   BIGINT,
    name        VARCHAR(128) NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(255),
    create_by   VARCHAR(64)  NOT NULL DEFAULT '',
    update_by   VARCHAR(64)  NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    remark      VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_sys_depart_parent ON sys_depart(parent_id) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_sys_depart_update_time ON sys_depart;
CREATE TRIGGER update_sys_depart_update_time BEFORE UPDATE ON sys_depart
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sys_depart IS '系统部门表（组织架构树，RBAC）';
COMMENT ON COLUMN sys_depart.parent_id IS '父部门 ID（NULL=顶级部门）';
COMMENT ON COLUMN sys_depart.status IS '状态：ACTIVE / DISABLED';

-- ------------------------------------------------------------
-- 2. sys_user_depart 用户-部门关联（多对多）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user_depart (
    id          BIGINT      NOT NULL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    depart_id   BIGINT      NOT NULL,
    create_by   VARCHAR(64) NOT NULL DEFAULT '',
    update_by   VARCHAR(64) NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    remark      VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_user_depart_unique ON sys_user_depart(user_id, depart_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sys_user_depart_user ON sys_user_depart(user_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sys_user_depart_depart ON sys_user_depart(depart_id) WHERE deleted = 0;

COMMENT ON TABLE sys_user_depart IS '系统用户-部门关联表（RBAC，多对多）';

-- ------------------------------------------------------------
-- 3. sys_position 岗位表 + sys_user_position 用户-岗位关联
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_position (
    id          BIGINT       NOT NULL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(255),
    create_by   VARCHAR(64)  NOT NULL DEFAULT '',
    update_by   VARCHAR(64)  NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    remark      VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_position_code ON sys_position(code) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_sys_position_update_time ON sys_position;
CREATE TRIGGER update_sys_position_update_time BEFORE UPDATE ON sys_position
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sys_position IS '系统岗位表（RBAC）';

CREATE TABLE IF NOT EXISTS sys_user_position (
    id          BIGINT      NOT NULL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    position_id BIGINT      NOT NULL,
    create_by   VARCHAR(64) NOT NULL DEFAULT '',
    update_by   VARCHAR(64) NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    remark      VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_user_position_unique ON sys_user_position(user_id, position_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sys_user_position_user ON sys_user_position(user_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sys_user_position_position ON sys_user_position(position_id) WHERE deleted = 0;

COMMENT ON TABLE sys_user_position IS '系统用户-岗位关联表（RBAC，多对多）';

-- ------------------------------------------------------------
-- 4. 权限码：部门/岗位（MENU 菜单项挂「系统设置」+ API 动作码）
--    仅 SUPER_ADMIN（"*" 通配）默认获得，延续管理类仅超管口径。
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, parent_id, path, icon, component, sort, create_by, update_by)
VALUES
    (40, 'depart:view',   '部门管理', 'MENU', 17, '/system/departs',   'OfficeBuilding', 'system/DepartList',   21, 'system', 'system'),
    (44, 'position:view', '岗位管理', 'MENU', 17, '/system/positions', 'Postcard',       'system/PositionList', 22, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (41, 'depart:add',        '部门-新增', 'API', 36, 'system', 'system'),
    (42, 'depart:edit',       '部门-编辑', 'API', 37, 'system', 'system'),
    (43, 'depart:delete',     '部门-删除', 'API', 38, 'system', 'system'),
    (45, 'position:add',      '岗位-新增', 'API', 39, 'system', 'system'),
    (46, 'position:edit',     '岗位-编辑', 'API', 40, 'system', 'system'),
    (47, 'position:delete',   '岗位-删除', 'API', 41, 'system', 'system')
ON CONFLICT (id) DO NOTHING;
