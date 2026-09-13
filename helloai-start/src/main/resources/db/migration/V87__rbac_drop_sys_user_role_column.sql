-- ============================================================
-- BASE-4.2 身份数据模型清理：sys_user.role 单字段退场
--
-- 背景（2026-09-13 代码核查）：
--   sys_user.role 已退化为「死字段」——
--     授权侧不读：SysPermissionQueryServiceImpl.listPermissionCode 只走
--                 sys_user_role → sys_role → sys_role_permission → sys_permission；
--     前端不消费：登录用 roles[]、用户列表用 roleCodes[]，均来自关联表；
--   而历史建号路径（SysUserServiceImpl.create / AdminInitializer）只写该字段、
--   不写 sys_user_role，导致「建了号却无角色」的空权限账号。
--
-- 本迁移做两件事：
--   1) 防御性补齐：按 legacy sys_user.role 为「当前无任何有效角色关联」的用户补建
--      sys_user_role 行（幂等，已有有效关联的用户一律不动，关联表为权威事实源）；
--   2) 删除 sys_user.role 列，使身份回归单事实源。
--
-- 红线：本迁移为新增迁移（V87），V1~V86 只读不改。
-- 不可逆性：第 2 步 DROP COLUMN 不可回滚；如需回退须手工 ADD COLUMN 并从
--           sys_user_role 回填（见《HelloAI 基础架构调整实施计划》§9.3 BASE-4.2）。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 防御性补齐：legacy 单字段 → sys_user_role（幂等）
--    仅补「无任何有效角色关联」的用户；ID 段取 90000000000002xxxx
--    与 V77 迁移段（90000000000001xxxx）错开，避免主键冲突。
-- ------------------------------------------------------------
INSERT INTO sys_user_role (id, user_id, role_id, create_by, update_by)
SELECT 9000000000000200000 + ROW_NUMBER() OVER (ORDER BY u.id),
       u.id,
       r.id,
       'system',
       'system'
FROM sys_user u
JOIN sys_role r ON r.code = u.role AND r.deleted = 0
WHERE u.deleted = 0
  AND u.role IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_role ur
      WHERE ur.user_id = u.id AND ur.deleted = 0
  )
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 2) 删除 legacy 单字段（身份回归 sys_user_role 单事实源）
--    V1 的 COMMENT ON COLUMN sys_user.role 随列一并移除，无需单独处理。
-- ------------------------------------------------------------
ALTER TABLE sys_user DROP COLUMN IF EXISTS role;
