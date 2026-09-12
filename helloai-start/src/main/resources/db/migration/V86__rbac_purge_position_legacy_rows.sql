-- ============================================================
-- RBAC 基础架构收敛（BASE-3.2 收口·补）：岗位遗留数据物理清理
-- 背景：V85 已移除岗位能力（软删 position:* 4 个权限码 + DROP 两张岗位表），
--       但 sys_permission 中仍留有 deleted=1 的 4 行「遗留数据」（查询层不可见）。
--       本次按要求将其**物理删除**，并把关联表可能存在的残留行一并清掉（其他环境防御）。
-- 影响：V77~V85 只读，本迁移 V86 递增；仅删岗位相关留痕，不改其他权限码/菜单结构。
--       执行后 V85 的「软删」结果被彻底移除，岗位相关记录在 sys_permission 不再存在。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 关联表残留物理清理（其他环境可能有显式授权行；本机实测均为 0 行）
-- ------------------------------------------------------------
DELETE FROM sys_role_permission
WHERE permission_id IN (SELECT id FROM sys_permission WHERE code LIKE 'position:%');

DELETE FROM sys_permission_data_rule
WHERE permission_id IN (SELECT id FROM sys_permission WHERE code LIKE 'position:%');

-- ------------------------------------------------------------
-- 2. 权限码遗留行物理删除（含 V85 软删的 deleted=1 行）
-- ------------------------------------------------------------
DELETE FROM sys_permission
WHERE code LIKE 'position:%';
