-- ============================================================
-- RBAC 基础架构深化（BASE-3.1）：菜单路由渲染增强
-- 背景：对齐 JeecgBoot 菜单渲染能力，补齐三类场景——
--       ① hidden：隐藏菜单（不在侧边栏显示，但路由仍可达，如详情页/聚合页）；
--       ② keep_alive：页面缓存（前端 keep-alive 保留组件状态）；
--       ③ external_link：外链菜单（点击新窗口打开外部系统，不注册前端路由）。
-- 影响：V77~V80 只读，本迁移 V81 递增；新增列带默认值，存量菜单行为不变。
-- ============================================================

ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS hidden SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS keep_alive SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS external_link VARCHAR(512);

COMMENT ON COLUMN sys_permission.hidden IS '隐藏菜单：0=显示（默认）1=隐藏（不在侧边栏显示，路由仍注册可达）';
COMMENT ON COLUMN sys_permission.keep_alive IS '页面缓存：0=不缓存（默认）1=缓存（前端 keep-alive 保留状态）';
COMMENT ON COLUMN sys_permission.external_link IS '外链地址（非空时点击新窗口打开，不注册前端路由）';
