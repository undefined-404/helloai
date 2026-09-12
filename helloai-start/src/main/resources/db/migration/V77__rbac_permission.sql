-- ============================================================
-- RBAC 权限底座：sys_role / sys_permission / sys_user_role / sys_role_permission
-- 背景：登录鉴权由自建 AuthService 升级为 Sa-Token；授权由 AdminOnlyInterceptor
--       的 /api/admin/** 前缀二元判断升级为「角色 - 权限码」RBAC 模型。
--       本迁移只建数据底座 + 内置种子 + 存量 sys_user.role 单字段迁移到关联表；
--       认证会话切换与 @SaCheckPermission 注解接入在应用层完成（见差距表 G-012）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. sys_role 角色表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT      NOT NULL PRIMARY KEY,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(255),
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    sort        INT         NOT NULL DEFAULT 0,
    create_by   VARCHAR(64) NOT NULL DEFAULT '',
    update_by   VARCHAR(64) NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    remark      VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_role_code ON sys_role(code) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_sys_role_update_time ON sys_role;
CREATE TRIGGER update_sys_role_update_time BEFORE UPDATE ON sys_role
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sys_role IS '系统角色表（RBAC）';
COMMENT ON COLUMN sys_role.code IS '角色编码：SUPER_ADMIN / ADMIN';
COMMENT ON COLUMN sys_role.name IS '角色名称';
COMMENT ON COLUMN sys_role.status IS '状态：ACTIVE / DISABLED';
COMMENT ON COLUMN sys_role.sort IS '排序（越小越靠前）';

-- ------------------------------------------------------------
-- 2. sys_permission 权限码表
--    权限码同时作为「前端菜单过滤」与「后端 @SaCheckPermission」的共享事实源。
--    type：MENU（菜单可见性）/ API（接口动作）；前端按 MENU 权限码过滤菜单显隐，
--    后端按 API 权限码校验写操作。sys_menu 树不建表——菜单结构仍在前端路由，
--    仅权限码落入 DB（符合「前端过滤 + 后端权限码」方向）。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_permission (
    id          BIGINT       NOT NULL PRIMARY KEY,
    code        VARCHAR(128) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(20)  NOT NULL DEFAULT 'MENU',
    sort        INT          NOT NULL DEFAULT 0,
    create_by   VARCHAR(64)  NOT NULL DEFAULT '',
    update_by   VARCHAR(64)  NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    remark      VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_permission_code ON sys_permission(code) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_sys_permission_update_time ON sys_permission;
CREATE TRIGGER update_sys_permission_update_time BEFORE UPDATE ON sys_permission
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sys_permission IS '系统权限码表（RBAC）';
COMMENT ON COLUMN sys_permission.code IS '权限码：如 task:view / agent:manage';
COMMENT ON COLUMN sys_permission.type IS '类型：MENU（菜单可见性）/ API（接口动作）';

-- ------------------------------------------------------------
-- 3. sys_user_role 用户-角色关联表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user_role (
    id          BIGINT      NOT NULL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    role_id     BIGINT      NOT NULL,
    create_by   VARCHAR(64) NOT NULL DEFAULT '',
    update_by   VARCHAR(64) NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT    NOT NULL DEFAULT 0,
    remark      VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_user_role_unique ON sys_user_role(user_id, role_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sys_user_role_user ON sys_user_role(user_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sys_user_role_role ON sys_user_role(role_id) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_sys_user_role_update_time ON sys_user_role;
CREATE TRIGGER update_sys_user_role_update_time BEFORE UPDATE ON sys_user_role
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sys_user_role IS '系统用户-角色关联表（RBAC，多对多）';

-- ------------------------------------------------------------
-- 4. sys_role_permission 角色-权限码关联表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id            BIGINT      NOT NULL PRIMARY KEY,
    role_id       BIGINT      NOT NULL,
    permission_id BIGINT      NOT NULL,
    create_by     VARCHAR(64) NOT NULL DEFAULT '',
    update_by     VARCHAR(64) NOT NULL DEFAULT '',
    create_time   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT    NOT NULL DEFAULT 0,
    remark        VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_role_permission_unique ON sys_role_permission(role_id, permission_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_sys_role_permission_role ON sys_role_permission(role_id) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_sys_role_permission_update_time ON sys_role_permission;
CREATE TRIGGER update_sys_role_permission_update_time BEFORE UPDATE ON sys_role_permission
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sys_role_permission IS '系统角色-权限码关联表（RBAC，多对多）';

-- ============================================================
-- 5. 内置种子数据（固定小 ID，与应用层雪花 ID 不冲突）
--    SUPER_ADMIN 为超级管理员，在应用层 StpInterface 中特殊处理为「全权限」，
--    不落关联表；ADMIN 显式绑定 20 个常用权限码。
-- ============================================================

-- 5.1 角色
INSERT INTO sys_role (id, code, name, description, status, sort, create_by, update_by, remark) VALUES
    (1, 'SUPER_ADMIN', '超级管理员', '拥有平台全部权限', 'ACTIVE', 1, 'system', 'system', '内置角色'),
    (2, 'ADMIN',       '管理员',     '平台日常管理权限',  'ACTIVE', 2, 'system', 'system', '内置角色')
ON CONFLICT (id) DO NOTHING;

-- 5.2 权限码：17 个菜单可见性 + 6 个接口动作
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by) VALUES
    (1,  'dashboard:view',   '概览',       'MENU', 1,  'system', 'system'),
    (2,  'task:view',        '任务管理',   'MENU', 2,  'system', 'system'),
    (3,  'task:create',      '对话新建',   'MENU', 3,  'system', 'system'),
    (4,  'subtask:view',     '子任务',     'MENU', 4,  'system', 'system'),
    (5,  'agent:view',       'Agent 管理', 'MENU', 5,  'system', 'system'),
    (6,  'team:view',        'Team 组合',  'MENU', 6,  'system', 'system'),
    (7,  'browser:view',     'Browser 会话', 'MENU', 7,  'system', 'system'),
    (8,  'review:view',      '审查中心',   'MENU', 8,  'system', 'system'),
    (9,  'event:view',       '事件流',     'MENU', 9,  'system', 'system'),
    (10, 'quality:view',     '质量看板',   'MENU', 10, 'system', 'system'),
    (11, 'reward:view',      '积分流水',   'MENU', 11, 'system', 'system'),
    (12, 'activity:view',    '活动流',     'MENU', 12, 'system', 'system'),
    (13, 'rule:view',        '规则配置',   'MENU', 13, 'system', 'system'),
    (14, 'duty:view',        '打卡上班',   'MENU', 14, 'system', 'system'),
    (15, 'inbox:view',       '收件箱',     'MENU', 15, 'system', 'system'),
    (16, 'attachment:view',  '附件管理',   'MENU', 16, 'system', 'system'),
    (17, 'settings:view',    '系统设置',   'MENU', 17, 'system', 'system'),
    (18, 'agent:manage',     'Agent 增删改', 'API', 18, 'system', 'system'),
    (19, 'task:assign',      '任务派发/重派', 'API', 19, 'system', 'system'),
    (20, 'review:approve',   '审查裁决',   'API', 20, 'system', 'system'),
    (21, 'system:manage',    '系统设置修改', 'API', 21, 'system', 'system'),
    (22, 'user:manage',      '用户管理',   'API', 22, 'system', 'system'),
    (23, 'role:manage',      '角色管理',   'API', 23, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- 5.3 角色-权限关联：ADMIN 拥有 17 个菜单 + agent:manage / task:assign / review:approve
--     （不含 user:manage / role:manage / system:manage，三者仅 SUPER_ADMIN）
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000000000 + ROW_NUMBER() OVER (ORDER BY p.id),
       2,
       p.id,
       'system',
       'system'
FROM sys_permission p
WHERE p.code IN (
    'dashboard:view', 'task:view', 'task:create', 'subtask:view', 'agent:view',
    'team:view', 'browser:view', 'review:view', 'event:view', 'quality:view',
    'reward:view', 'activity:view', 'rule:view', 'duty:view', 'inbox:view',
    'attachment:view', 'settings:view', 'agent:manage', 'task:assign', 'review:approve'
)
ON CONFLICT (id) DO NOTHING;

-- 5.4 存量用户迁移：sys_user.role 单字段 → sys_user_role 多对多
--     role='SUPER_ADMIN' → 绑角色 1；其余（含 'ADMIN'）→ 绑角色 2
INSERT INTO sys_user_role (id, user_id, role_id, create_by, update_by)
SELECT 9000000000000100000 + ROW_NUMBER() OVER (ORDER BY id),
       id,
       CASE WHEN role = 'SUPER_ADMIN' THEN 1 ELSE 2 END,
       'system',
       'system'
FROM sys_user
WHERE deleted = 0
ON CONFLICT (id) DO NOTHING;