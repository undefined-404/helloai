-- ============================================================
-- 技术债清理（§9.9.2 #1 / #2）：平台配置页归位为「系统设置」子菜单
--
-- 问题：
--   · Settings.vue（LLM 供应商 / 基础配置 / 联网搜索 管理页）**不可达**——
--     `settings:view`（id=17）的 component 为 NULL，实际承担的是「系统设置」**聚合父菜单**角色；
--     `/settings` 会被 dynamic.ts redirect 到首个可见子，且前端 router 中无任何 Settings.vue 引用；
--   · 语义因此混用：ADMIN 持有 `settings:view`（仅因「部门管理」子菜单需要父菜单可见），
--     却不持有任何平台配置动作码 —— 父菜单同时承担了「结构」与「页面入口」两种含义。
--
-- 做法：把平台配置页**显式登记为「系统设置」下的子菜单**
--       （path=/system/platform，component=Settings），`settings:view` 回归纯聚合父语义。
--       仅 SUPER_ADMIN 可见（其权限为 "*" 通配，无需角色绑定）；ADMIN 不授予
--       —— 延续 §9.4.4「平台配置类归 SUPER_ADMIN 专有」口径。
--       id=120 为 V88 段（49~119）之后的空闲小号（>48 的存量行均为雪花 ID 测试残留，已在 V91 清理）。
--
-- 红线：V1~V91 只读，本迁移递增为 V92；仅新增一行 MENU，不改任何权限码与角色绑定。
-- 回滚：DELETE FROM sys_permission WHERE code = 'platform-config:view';
-- ============================================================

INSERT INTO sys_permission (id, code, name, type, parent_id, path, icon, component, sort, create_by, update_by)
VALUES (120, 'platform-config:view', '平台配置', 'MENU', 17, '/system/platform', 'Setting', 'Settings', 22, 'system', 'system')
ON CONFLICT (id) DO NOTHING;
