-- ============================================================
-- 回填脚本：改派时间线事件（2026-09-01，LOG-20260901-002 配套）
-- ------------------------------------------------------------
-- 背景：「超时未领取改派」「执行超时改派」「离线改派」三个专属时间线事件
--   （sub_task_unclaimed_timeout_reassign / sub_task_execution_timeout_reassign /
--    sub_task_offline_reassign）于 2026-09-01 上线，此前发生的改派在存量
--   子任务时间线上无留痕。本脚本依据存量证据回填，让历史任务的调度过程同样可观测。
--
-- 证据锚点：
--   ① 超时未领取：存量 sub_task_dispatch_prepare 且 payload.trigger='assigned_timeout'
--      （该事件旧代码就已记录，其时刻即"判定超时并改派"时刻）
--   ② 执行超时：agent_execution_record status='TIMEOUT' 且 error_msg='执行命令超时'
--      （markTimeout 落 end_time 的时刻即补偿判定时刻）
--   ③ 离线改派：存量 sub_task_dispatch_prepare 且 payload.trigger='agent_offline'
--      （离线巡检发现 Agent 心跳丢失后重派，生产实测的主要改派路径）
--
-- id 策略：时间线按 id 升序渲染（雪花 id 随时间单调），回填行 id 取
--   "目标时刻前后相邻真实事件 id 的中点"，保证渲染顺序与真实时序一致。
--
-- 幂等性：NOT EXISTS 守卫，重复执行不产生重复行。
-- 执行方式：先跑「第 0 段」预览核对，再整段执行（事务内，异常自动回滚）。
-- ============================================================

-- ────────────────────────────────────────────────
-- 第 0 段：预览（只读）——先确认将被回填的子任务
-- ────────────────────────────────────────────────

-- 0.1 超时未领取改派候选
SELECT p.sub_task_id,
       p.agent_id                    AS original_agent_id,
       p.create_time                 AS reassign_time,
       p.payload ->> 'previousAgentId' AS payload_previous
FROM task_timeline p
WHERE p.deleted = 0
  AND p.event_type = 'sub_task_dispatch_prepare'
  AND p.payload ->> 'trigger' = 'assigned_timeout'
  AND NOT EXISTS (
      SELECT 1 FROM task_timeline e
      WHERE e.deleted = 0
        AND e.sub_task_id = p.sub_task_id
        AND e.event_type = 'sub_task_unclaimed_timeout_reassign'
        AND e.create_time BETWEEN p.create_time - interval '5 minutes'
                              AND p.create_time + interval '5 minutes')
ORDER BY p.create_time;

-- 0.2 执行超时改派候选
SELECT r.id AS record_id, r.sub_task_id, r.agent_id, r.event_id,
       r.end_time AS timeout_time
FROM agent_execution_record r
WHERE r.deleted = 0
  AND r.status = 'TIMEOUT'
  AND r.error_msg = '执行命令超时'
  AND NOT EXISTS (
      SELECT 1 FROM task_timeline e
      WHERE e.deleted = 0
        AND e.sub_task_id = r.sub_task_id
        AND e.event_type = 'sub_task_execution_timeout_reassign'
        AND e.create_time BETWEEN r.end_time - interval '5 minutes'
                              AND r.end_time + interval '5 minutes')
ORDER BY r.end_time;

-- 0.3 离线改派候选（生产实测主要路径：外部 Agent 心跳丢失 → 巡检改派）
SELECT p.sub_task_id,
       p.agent_id                    AS offline_agent_id,
       p.create_time                 AS reassign_time,
       p.payload ->> 'previousAgentId' AS payload_previous
FROM task_timeline p
WHERE p.deleted = 0
  AND p.event_type = 'sub_task_dispatch_prepare'
  AND p.payload ->> 'trigger' = 'agent_offline'
  AND NOT EXISTS (
      SELECT 1 FROM task_timeline e
      WHERE e.deleted = 0
        AND e.sub_task_id = p.sub_task_id
        AND e.event_type = 'sub_task_offline_reassign'
        AND e.create_time BETWEEN p.create_time - interval '5 minutes'
                              AND p.create_time + interval '5 minutes')
ORDER BY p.create_time;

-- ────────────────────────────────────────────────
-- 第 1 段：回填执行（确认预览无误后整段执行）
-- ────────────────────────────────────────────────
BEGIN;

-- 1.1 超时未领取改派
--   create_time 取 dispatch_prepare 同一时刻（同一调度动作的两面），
--   中点 id 保证渲染时恰排在「准备派单」之前。
INSERT INTO task_timeline (id, task_id, sub_task_id, event_type, role, agent_id,
                           payload, deleted, create_by, update_by, create_time, update_time)
WITH src AS (
    SELECT p.task_id, p.sub_task_id, p.agent_id, p.create_time,
           COALESCE(p.payload ->> 'role', 'EXECUTOR') AS role_name,
           -- 目标时刻之前的最近一条真实事件（回填行天然不在窗口内，递归安全）
           (SELECT x.id FROM task_timeline x
            WHERE x.deleted = 0 AND x.sub_task_id = p.sub_task_id
              AND (x.create_time, x.id) < (p.create_time, p.id)
            ORDER BY x.create_time DESC, x.id DESC LIMIT 1) AS lo,
           p.id AS hi
    FROM task_timeline p
    WHERE p.deleted = 0
      AND p.event_type = 'sub_task_dispatch_prepare'
      AND p.payload ->> 'trigger' = 'assigned_timeout'
      AND NOT EXISTS (
          SELECT 1 FROM task_timeline e
          WHERE e.deleted = 0
            AND e.sub_task_id = p.sub_task_id
            AND e.event_type = 'sub_task_unclaimed_timeout_reassign'
            AND e.create_time BETWEEN p.create_time - interval '5 minutes'
                                  AND p.create_time + interval '5 minutes')
)
SELECT CASE
           WHEN lo IS NOT NULL THEN (lo + hi) / 2      -- 前后相邻真实事件中点
           ELSE hi - 1000000                            -- 无前置事件：紧邻其前
       END,
       task_id, sub_task_id,
       'sub_task_unclaimed_timeout_reassign', 'SYSTEM', agent_id,
       jsonb_build_object('previousAgentId', agent_id, 'role', role_name,
                          'backfilled', true),
       0, 'backfill-20260901', 'backfill-20260901', create_time, create_time
FROM src;

-- 1.2 执行超时改派
--   create_time 取执行记录 end_time（markTimeout 判定时刻）。
INSERT INTO task_timeline (id, task_id, sub_task_id, event_type, role, agent_id,
                           payload, deleted, create_by, update_by, create_time, update_time)
WITH src AS (
    SELECT st.task_id, r.sub_task_id, r.agent_id, r.end_time AS event_time,
           r.event_id AS exec_event_id,
           -- 目标时刻前后相邻真实事件（跨 5 分钟补偿窗口做中点插值）
           (SELECT x.id FROM task_timeline x
            WHERE x.deleted = 0 AND x.sub_task_id = r.sub_task_id
              AND (x.create_time, x.id) < (r.end_time, 9223372036854775807::bigint)
            ORDER BY x.create_time DESC, x.id DESC LIMIT 1) AS lo,
           (SELECT x.id FROM task_timeline x
            WHERE x.deleted = 0 AND x.sub_task_id = r.sub_task_id
              AND x.create_time > r.end_time
            ORDER BY x.create_time ASC, x.id ASC LIMIT 1) AS hi
    FROM agent_execution_record r
    JOIN sub_task st ON st.id = r.sub_task_id AND st.deleted = 0
    WHERE r.deleted = 0
      AND r.status = 'TIMEOUT'
      AND r.error_msg = '执行命令超时'
      AND NOT EXISTS (
          SELECT 1 FROM task_timeline e
          WHERE e.deleted = 0
            AND e.sub_task_id = r.sub_task_id
            AND e.event_type = 'sub_task_execution_timeout_reassign'
            AND e.create_time BETWEEN r.end_time - interval '5 minutes'
                                  AND r.end_time + interval '5 minutes')
)
SELECT CASE
           WHEN lo IS NOT NULL AND hi IS NOT NULL THEN (lo + hi) / 2
           WHEN lo IS NOT NULL THEN lo + 1000000        -- 无后续事件：紧随其后
           ELSE hi - 1000000                            -- 无前置事件：紧邻其前
       END,
       task_id, sub_task_id,
       'sub_task_execution_timeout_reassign', 'SYSTEM', agent_id,
       jsonb_build_object('previousAgentId', COALESCE(agent_id, 0),
                          'timeoutMinutes', 10,         -- 按当前生产配置核实后修改
                          'eventId', COALESCE(exec_event_id, ''),
                          'backfilled', true),
       0, 'backfill-20260901', 'backfill-20260901', event_time, event_time
FROM src;

-- 1.3 离线改派
--   create_time 取 dispatch_prepare 同一时刻（同一调度动作的两面），
--   中点 id 保证渲染时恰排在「准备派单」之前（与后端新代码记录顺序一致）。
INSERT INTO task_timeline (id, task_id, sub_task_id, event_type, role, agent_id,
                           payload, deleted, create_by, update_by, create_time, update_time)
WITH src AS (
    SELECT p.task_id, p.sub_task_id, p.agent_id, p.create_time,
           (SELECT x.id FROM task_timeline x
            WHERE x.deleted = 0 AND x.sub_task_id = p.sub_task_id
              AND (x.create_time, x.id) < (p.create_time, p.id)
            ORDER BY x.create_time DESC, x.id DESC LIMIT 1) AS lo,
           p.id AS hi
    FROM task_timeline p
    WHERE p.deleted = 0
      AND p.event_type = 'sub_task_dispatch_prepare'
      AND p.payload ->> 'trigger' = 'agent_offline'
      AND NOT EXISTS (
          SELECT 1 FROM task_timeline e
          WHERE e.deleted = 0
            AND e.sub_task_id = p.sub_task_id
            AND e.event_type = 'sub_task_offline_reassign'
            AND e.create_time BETWEEN p.create_time - interval '5 minutes'
                                  AND p.create_time + interval '5 minutes')
)
SELECT CASE
           WHEN lo IS NOT NULL THEN (lo + hi) / 2      -- 前后相邻真实事件中点
           ELSE hi - 1000000                            -- 无前置事件：紧邻其前
       END,
       task_id, sub_task_id,
       'sub_task_offline_reassign', 'SYSTEM', agent_id,
       jsonb_build_object('previousAgentId', agent_id, 'backfilled', true),
       0, 'backfill-20260901', 'backfill-20260901', create_time, create_time
FROM src;

COMMIT;

-- ────────────────────────────────────────────────
-- 第 2 段：执行后校验（只读）
-- ────────────────────────────────────────────────
SELECT event_type, COUNT(*) AS cnt
FROM task_timeline
WHERE deleted = 0
  AND event_type IN ('sub_task_unclaimed_timeout_reassign',
                     'sub_task_execution_timeout_reassign',
                     'sub_task_offline_reassign')
GROUP BY event_type;

-- 抽查某个子任务的时间线顺序（把 :subTaskId 换成实际 ID）：
-- SELECT id, event_type, agent_id, create_time
-- FROM task_timeline
-- WHERE deleted = 0 AND sub_task_id = :subTaskId
-- ORDER BY id;

-- ────────────────────────────────────────────────
-- 回滚（如需撤销全部回填行）
-- ────────────────────────────────────────────────
-- DELETE FROM task_timeline
-- WHERE deleted = 0
--   AND create_by = 'backfill-20260901'
--   AND event_type IN ('sub_task_unclaimed_timeout_reassign',
--                      'sub_task_execution_timeout_reassign',
--                      'sub_task_offline_reassign');
