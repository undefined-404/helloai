-- ============================================================
-- RBAC 基础架构深化（BASE-3.2 授权补充）：ADMIN 角色绑定部门管理权限
-- 背景：部门/岗位（V82）默认仅 SUPER_ADMIN（"*"）可用；按需将「部门管理」
--       授予 ADMIN 角色（view 菜单 + add/edit/delete 动作码），使其可自助维护组织架构。
-- 说明：与「角色管理 → 分配权限」界面操作等效；无条件 ON CONFLICT DO NOTHING
--       保证幂等（已通过界面绑定过的环境不会重复插入，也不会因唯一索引冲突失败）。
-- 影响：V77~V83 只读，本迁移 V84 递增；仅新增关联行，不改权限码/菜单结构。
-- ============================================================

INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000300000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.code IN ('depart:view', 'depart:add', 'depart:edit', 'depart:delete')
  AND p.deleted = 0
ON CONFLICT DO NOTHING;
