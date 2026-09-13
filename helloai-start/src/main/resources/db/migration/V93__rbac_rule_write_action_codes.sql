-- ============================================================
-- 规则配置写接口动作码 + 角色绑定（GUEST 保持只读）
--
-- 背景（2026-09-13 代码核查）：规则配置页（rule:view）此前只有只读入口，
--   页面上的「新建/编辑/删除」按钮既无动作码、后端也没有对应接口
--   （实测 POST /api/rules → 405，PUT/DELETE 无映射），对所有角色都是坏按钮。
--   本次补齐：sys_permission 动作码 + 角色绑定 + RulesController 写接口。
--
-- 绑定口径（与 V89 角色分层一致）：
--   ADMIN(2)      业务读写 → 绑
--   NORMAL_USER(3) 业务读写 → 绑
--   GUEST(4)      纯只读   → 不绑（保持 403，前端按钮 v-auth 随之隐藏）
--
-- 红线：V1~V92 只读，本迁移递增为 V93；仅新增权限码与角色关联行，不新增权限体系。
-- 回滚：按 code 反向删 sys_role_permission / sys_permission 即可（无 DDL）。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 权限码事实源（id 121~123，接续 V88 的 118 与 V92 的 120）
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (121, 'rule:add',    '规则-新建', 'API', 113, 'system', 'system'),
    (122, 'rule:edit',   '规则-编辑', 'API', 114, 'system', 'system'),
    (123, 'rule:delete', '规则-删除', 'API', 115, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 2) 角色绑定：ADMIN(2)
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN ('rule:add', 'rule:edit', 'rule:delete')
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 3) 角色绑定：NORMAL_USER(3)
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000500000 + p.id, 3, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN ('rule:add', 'rule:edit', 'rule:delete')
ON CONFLICT DO NOTHING;
