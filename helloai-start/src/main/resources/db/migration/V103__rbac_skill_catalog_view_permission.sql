-- ============================================================
-- 技能目录查看动作码 + 角色绑定（REF-1.2c：技能包元数据迁移到 md frontmatter 后，新增目录查询 API）
--
-- 背景（2026-10-09）：技能包元数据由 Java KNOWN_SPECS 硬编码改为 classpath
--   `skills/plugins/*.md` 的 YAML frontmatter + 目录扫描，前端不再保留对齐副本
--   （原 helloai-ui/src/constants/agentSkills.ts 的 ENG_SKILL_OPTIONS / skillLabelOf 删除），
--   改为经本端点服务端下发。消费方为 NORMAL_USER 可达页面（任务表单技能下拉、
--   拆解草案的平台技能判定、子任务详情标签），故必须绑定 NORMAL_USER。
--
-- 绑定口径（与 V89 角色分层、V93 体例一致）：
--   ADMIN(2)      业务读写 → 绑
--   NORMAL_USER(3) 业务读写 → 绑
--   GUEST(4)      纯只读   → 不绑（保持 403）
--
-- 不建菜单行：本端点是弹窗数据源，不是独立页面。
-- 红线：V1~V102 只读，本迁移递增为 V103；仅新增权限码与角色关联行，不新增权限体系、无 DDL。
-- 回滚：按 code 反向删 sys_role_permission / sys_permission 即可（无 DDL）。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 权限码事实源（id 124，接续 V93 的 121~123）
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (124, 'skill:view', '技能目录-查看', 'API', 116, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 2) 角色绑定：ADMIN(2)
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code = 'skill:view'
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 3) 角色绑定：NORMAL_USER(3)
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000500000 + p.id, 3, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code = 'skill:view'
ON CONFLICT DO NOTHING;
