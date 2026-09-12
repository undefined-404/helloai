-- ============================================================
-- RBAC 基础架构深化（BASE-1.1 补丁）：补齐缺失菜单 path/icon
-- 背景：V78 迁移时 UPDATE 了 14 个存量菜单的 path/icon，
--       遗漏 reward:view(11) / activity:view(12) / rule:view(13) 三个，
--       导致前端动态路由（BASE-1.3）因缺 path 无法注册这三个页面。
-- 本迁移补齐三者 path/icon（对齐原前端静态路由与 Element Plus 图标命名）。
-- ============================================================

UPDATE sys_permission SET path = '/rewards', icon = 'Trophy'       WHERE code = 'reward:view';
UPDATE sys_permission SET path = '/activity', icon = 'TrendCharts' WHERE code = 'activity:view';
UPDATE sys_permission SET path = '/rules', icon = 'SetUp'          WHERE code = 'rule:view';
