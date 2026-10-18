-- V116__hide_reward_menu.sql
-- 目的：隐藏「积分流水」独立侧边栏菜单（2026-10-18）
--
-- 背景：积分流水（reward:view，path=/rewards）本质是「Agent 维度的加减分明细」，
--   与 Agent 详情页「积分明细」重复（AgentDetail.vue 已按 agentId 查流水，
--   接口 /api/scores/listLogs 支持 agentId 过滤）。独立菜单只提供全局视角，
--   而用户真实诉求是「这个 Agent 为什么加了/扣了分」⇒ 并入 Agent 详情更贴使用场景。
--
-- 做法：逻辑删除该菜单行（MyBatis-Plus 自动按 deleted=0 过滤，菜单树不再下发）。
--   · 页面与接口全部保留（/rewards 路由仍可直达，ScoreController 契约不变）；
--   · 不做物理删除，便于回滚：把 deleted 改回 0 即恢复菜单。
--
-- 配套（同批前端改动）：Agent 详情「积分明细」上方新增「评分口径说明」
--   （五维权重 / 等级阈值 / 何时评分），解决「有评分但没有评分说明」的另一个问题。

UPDATE sys_permission
SET deleted     = 1,
    update_by   = 'system',
    update_time = CURRENT_TIMESTAMP
WHERE code = 'reward:view'
  AND deleted = 0;
