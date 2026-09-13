-- ============================================================
-- 技术债清理（§9.9.2 #3）：sys_permission 历史测试残留物理清理
--
-- 背景：dev 库中残留 6 行早期验证产生的测试权限码（均为 deleted=1 的雪藏 ID 行）：
--   DOCKER:TEST / DOCKER:CHILD / PERM:PROBE / UI:TEST / TMP:MENU:VERIFY / TMP:MENU:VERIFY2
--   它们既非业务功能，也非种子数据，长期占用 sys_permission 造成盘点噪声。
--
-- 做法（与 V86 清理岗位残留同构）：先防御性清关联行，再按 code 精确物理删除。
-- 幂等：WHERE code IN (...) + 关联表先删；干净环境执行结果为 0 行。
--
-- 红线：V1~V90 只读，本迁移递增为 V91；仅删测试残留，不触碰任何真实权限码。
-- 回滚：不可逆（物理删除）；如需恢复只能重建同名测试数据。
-- ============================================================

-- 1) 防御性清理关联行（角色-权限 / 数据规则）
DELETE FROM sys_role_permission
WHERE permission_id IN (
    SELECT id FROM sys_permission
    WHERE code IN ('DOCKER:TEST', 'DOCKER:CHILD', 'PERM:PROBE',
                   'UI:TEST', 'TMP:MENU:VERIFY', 'TMP:MENU:VERIFY2')
);

DELETE FROM sys_permission_data_rule
WHERE permission_id IN (
    SELECT id FROM sys_permission
    WHERE code IN ('DOCKER:TEST', 'DOCKER:CHILD', 'PERM:PROBE',
                   'UI:TEST', 'TMP:MENU:VERIFY', 'TMP:MENU:VERIFY2')
);

-- 2) 按 code 精确物理删除（含任意 deleted 状态）
DELETE FROM sys_permission
WHERE code IN ('DOCKER:TEST', 'DOCKER:CHILD', 'PERM:PROBE',
               'UI:TEST', 'TMP:MENU:VERIFY', 'TMP:MENU:VERIFY2');
