-- ============================================================
-- RBAC 基础架构收敛（BASE-3.2 收口 + BASE-2.2 收口，2026-09-12）
-- 背景：
--   ① 岗位（position）对当前系统无实际场景——部门已承担「组织归属 + 数据权限范围」，
--      岗位无任何权限/审批/路由配套，属对齐 JeecgBoot 时引入的冗余能力，经确认「彻底移除」。
--   ② 「菜单维护」页面此前与权限管理同页（PermissionList.vue），入口名不直观、不可发现，
--      拆出独立「菜单管理」入口（仅 type=MENU 树形 CRUD；权限管理收敛为 type=API 权限码）。
-- 影响：V77~V84 只读，本迁移 V85 递增。
--       · 岗位移除的数据影响为 0：sys_position / sys_user_position 均无业务数据（0 行），
--         position:* 权限码无任何角色显式关联（仅 SUPER_ADMIN "*" 通配隐式可用）。
--       · DROP TABLE 不可逆：如需恢复，需按 V82 的岗位段落重建表与权限码。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 岗位权限码清除（先清关联，再软删权限码；防御性覆盖 data_rule）
-- ------------------------------------------------------------
DELETE FROM sys_role_permission
WHERE permission_id IN (SELECT id FROM sys_permission WHERE code LIKE 'position:%');

DELETE FROM sys_permission_data_rule
WHERE permission_id IN (SELECT id FROM sys_permission WHERE code LIKE 'position:%');

-- 软删（deleted=1）：菜单树 / 权限列表 / 授权勾选均按 deleted=0 过滤，软删后不可见且释放 code 唯一索引
UPDATE sys_permission
SET deleted = 1, update_by = 'system'
WHERE code LIKE 'position:%' AND deleted = 0;

-- ------------------------------------------------------------
-- 2. 岗位表彻底删除（sys_user_position 先于 sys_position）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS sys_user_position;
DROP TABLE IF EXISTS sys_position;

-- ------------------------------------------------------------
-- 3. 新增「菜单管理」独立入口（MENU，挂「系统设置」id=17）
--    说明：菜单与权限码同属 sys_permission 单一实体，故写操作复用既有动作码
--          permission:add / permission:edit / permission:delete（后端 /api/admin/permissions 不变）；
--          menu:view 仅作为该菜单入口的可见性/路由可达性权限码。
--    id=48 为 V77~V84 未占用的空闲小号（>47 的存量行均为雪花 ID，不冲突）。
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, parent_id, path, icon, component, sort, create_by, update_by)
VALUES (48, 'menu:view', '菜单管理', 'MENU', 17, '/system/menus', 'Menu', 'system/MenuList', 19, 'system', 'system')
ON CONFLICT (id) DO NOTHING;
