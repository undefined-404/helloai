-- ============================================================
-- 菜单「默认首页」标记
--
-- 登录后不再写死 /dashboard，而是落到被标记为默认首页的菜单；
-- 未标记、或当前用户无权访问该菜单时，前端回退到其菜单树中的第一个可见可路由页面。
-- 全平台仅允许一个默认首页（部分唯一索引保证），由菜单管理页的开关维护。
-- ============================================================

ALTER TABLE sys_permission
    ADD COLUMN IF NOT EXISTS is_default_home SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sys_permission.is_default_home IS '默认首页：0=否（默认）1=是（全平台唯一，登录后落到该菜单 path）';

-- 现状默认落到个人工作台（若该菜单存在）
UPDATE sys_permission
SET is_default_home = 1
WHERE code = 'workbench:view'
  AND type = 'MENU'
  AND deleted = 0;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_permission_default_home
    ON sys_permission (is_default_home)
    WHERE is_default_home = 1 AND deleted = 0;