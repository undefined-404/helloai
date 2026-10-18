-- V115__hide_deadletter_menu.sql
-- 目的：隐藏「死信池」独立侧边栏菜单（2026-10-18）
--
-- 背景：死信池菜单（V78 引入，id=27 / code=deadletter:view）的 path 是
--   /sub-tasks?status=DEAD_LETTER —— 即「子任务列表 + 预设状态筛选」，不是独立页面
--   （见 helloai-ui/src/router/dynamic.ts 注释：path 含 '?' 的菜单仅侧边栏入口，不注册路由）。
--   侧边栏同时出现「子任务」与「死信池」两个指向同一页面的入口，用户会误认为是两个模块。
--
-- 做法：逻辑删除该菜单行（MyBatis-Plus 自动按 deleted=0 过滤，listMenuTree 不再下发）。
--   · 保留 code=deadletter:view 的历史绑定行不动（无人以此判定权限，见全仓 grep 零命中）；
--   · 不做物理删除，便于回滚：如需恢复菜单，把 deleted 改回 0 即可。
--
-- 替代入口：子任务列表页新增「死信待处理 N」按钮（带待处理数量角标），点击即筛选
--   status=DEAD_LETTER，等价于原菜单效果，且始终伴随数量提示。

UPDATE sys_permission
SET deleted     = 1,
    update_by   = 'system',
    update_time = CURRENT_TIMESTAMP
WHERE code = 'deadletter:view'
  AND deleted = 0;
