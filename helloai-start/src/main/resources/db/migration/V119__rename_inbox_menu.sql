-- V119__rename_inbox_menu.sql
-- 目的：侧边栏菜单「收件箱」更名为「Agent 收件箱」（2026-10-18）
--
-- 背景：`agent_inbox` 是**按 Agent 维度投递**的消息表（一行必属某个 agent_id），
--   管理端页面看的是「全平台各 Agent 收到的通知」。原名「收件箱」是 Human 视角，
--   让人误以为是「我的（管理员）收件箱」，与「为什么里面的东西都不是发给我的」直接冲突。
--   更名后语义即显式：这是 Agent 的收件箱，管理端只是在旁观察。
--
-- 做法：只改 `sys_permission.name`（菜单树由该列下发，前端不硬编码标签），
--   permission code `inbox:view` 不变 ⇒ 角色绑定、后端鉴权、前端路由一律零改动。
--
-- 配套（同批前端改动）：
--   · `helloai-ui/src/router/dynamic.ts` 路由 meta.title → 'Agent 收件箱'
--   · `helloai-ui/src/views/inbox/AgentInbox.vue` 卡片标题 → 'Agent 收件箱'
--
-- 回滚：把 name 改回 '收件箱' 即可。

UPDATE sys_permission
SET name        = 'Agent 收件箱',
    update_by   = 'system',
    update_time = CURRENT_TIMESTAMP
WHERE code = 'inbox:view'
  AND deleted = 0;
