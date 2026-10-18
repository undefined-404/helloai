-- V118__backfill_external_agent_activity.sql
-- 目的：为历史外部 Agent 子任务补齐「提交产出」活动记录（2026-10-18）
--
-- 背景：平台此前不写 activity_log（MCP 无写活动工具、手册又把活动日志归为「低频不展开」），
--   导致外部 Agent 执行完的子任务在「活动流」里零记录（如 deadletter-subtask-20261006041222）。
--   代码侧已由 V118 之前的提交补上「提交时自动写 delivery」，但只覆盖此后新发生的提交。
--
-- 本迁移 = 一次性历史回填：
--   范围：status=DONE 且执行者为 CLI_CLIENT（外部 Agent）且该子任务**当前无任何活动记录**
--   内容：每个子任务补 1 条 action='delivery'，标记 source='backfill'（与实时写入区分）
--   时间：沿用子任务 update_time（保留原文时间感，不伪造为当前时间）
--
-- 幂等：NOT EXISTS 保证重复执行不新增；id 段固定（3100000000000000001 起）避免与 snowflake 冲突。
-- 可回滚：DELETE FROM activity_log WHERE source = 'backfill';
--
-- 注意：只补「交付」这一个节点（平台可确认的事实）。
--   过程节点（coding/blocked/reflection）无法凭空重建，不做伪造。

INSERT INTO activity_log (
    id, agent_id, sub_task_id, action, detail, level, source,
    create_by, update_by, create_time, update_time, deleted, remark
)
SELECT
    3100000000000000000 + row_number() OVER (ORDER BY st.id) AS id,
    st.assigned_agent_id,
    st.id,
    'delivery',
    jsonb_build_object(
        'backfill', true,
        'source', 'platform_backfill',
        'subTaskTitle', st.title,
        'finishedAt', st.update_time,
        'note', '历史回填：平台此前未记录外部 Agent 的提交活动，按子任务完成事实补写'
    ),
    'INFO',
    'backfill',
    'system',
    'system',
    COALESCE(st.update_time, CURRENT_TIMESTAMP),
    COALESCE(st.update_time, CURRENT_TIMESTAMP),
    0,
    'V118 历史回填（外部 Agent 提交活动）'
FROM sub_task st
JOIN agent a ON a.id = st.assigned_agent_id
WHERE st.deleted = 0
  AND st.status = 'DONE'
  AND st.assigned_agent_id IS NOT NULL
  AND a.access_type = 'CLI_CLIENT'
  AND NOT EXISTS (
      SELECT 1 FROM activity_log al
      WHERE al.sub_task_id = st.id AND al.deleted = 0
  )
ORDER BY st.id
ON CONFLICT (id) DO NOTHING;
