-- ============================================================
-- 观察期统计：交付物登记闸（MA-4 第一段，2026-11-19 配套）
-- ------------------------------------------------------------
-- 背景：交付物登记闸（warn-only）落地后有至少一周观察期，
--   核对 `delivery_gate_miss` 误报率（「有产物却被记缺失」的比例），
--   观察期结论三选一：升级第二段（可见）/ 维持第一段 / 关闭闸门。
--
-- 本脚本只读，不产生任何写入。执行方式：本地经 `.tmp/PgExec.java`
--   （java -cp pg.jar PgExec <conn> postgres postgres < 本文件），
--   或任意 psql 客户端整文件执行。
--
-- 数据来源：
--   ① 事件侧：`task_timeline`，event_type='delivery_gate_miss'，
--      payload 含 partsHit / attachmentHit / outputHit / outputLength /
--      exempted / exemptReason（ExecutionResultHandler 写点，见
--      core/agent/command/ExecutionResultHandler.java）。
--   ② 产物侧：`sub_task.context->'lastExecution'->>'output'`（冻结产出，
--      与 MA-1 读侧同一事实源），衡量「实际有产物却没被闸门识别」。
-- ============================================================

-- ────────────────────────────────────────────────
-- 1. 命中分布：delivery_gate_miss 事件按判据命中维度聚合
--    （判断每个事件「落后」在哪一叠：三判据都未命中 = 疑似真缺失；
--     有判据命中却仍记事件 = 不该发生，需排查写点）
-- ────────────────────────────────────────────────
SELECT (payload ->> 'partsHit')::boolean       AS parts_hit,
       (payload ->> 'attachmentHit')::boolean  AS attachment_hit,
       (payload ->> 'outputHit')::boolean      AS output_hit,
       (payload ->> 'exempted')::boolean       AS exempted,
       COUNT(*)                                AS cnt,
       MIN(create_time::date)                  AS first_day,
       MAX(create_time::date)                  AS last_day
FROM task_timeline
WHERE deleted = 0
  AND event_type = 'delivery_gate_miss'
GROUP BY 1, 2, 3, 4
ORDER BY cnt DESC;

-- ────────────────────────────────────────────────
-- 2. 每日频次：同一天内的登记数（观察期「何时告警变多」）
-- ────────────────────────────────────────────────
SELECT create_time::date AS day,
       COUNT(*)          AS cnt
FROM task_timeline
WHERE deleted = 0
  AND event_type = 'delivery_gate_miss'
GROUP BY 1
ORDER BY 1;

-- ────────────────────────────────────────────────
-- 3. 产出长度分布：全量子任务冻结 output 的长度分位
--    （判 `helloai.delivery.min-output-length` 阈值的依据；
--      对照 §2.2 事后口径：只看「成功提交且 ENTERED_DONE」的子任务，
--      避免把进行中的空 output 也拉进来）
-- ────────────────────────────────────────────────
WITH submitted AS (
    SELECT id,
           LENGTH(COALESCE(context -> 'lastExecution' ->> 'output', '')) AS output_len
    FROM sub_task
    WHERE deleted = 0
      AND status IN ('REVIEWING', 'DONE', 'REWORK')
      AND context ? 'lastExecution'          -- 只统计已产出过的（写点：ExecutionResultHandler）
),
pct AS (
    SELECT COUNT(*)                                AS n,
           PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY output_len) AS p50,
           PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY output_len) AS p90,
           PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY output_len) AS p99,
           MAX(output_len)                         AS max_len
    FROM submitted
)
SELECT n, p50::bigint, p90::bigint, p99::bigint, max_len
FROM pct;

-- ────────────────────────────────────────────────
-- 4. 误报率核对（观察期结束时跑）：被记缺失、但实际产出非空的子任务
--    （对照口径：以事件时间为锚，找该子任务当时的冻结 output）
--    output_len > 0 且事件里 outputHit=false ⇒ 「有产物却没被识别」，人工复核。
-- ────────────────────────────────────────────────
SELECT t.sub_task_id,
       t.create_time,
       t.payload ->> 'outputLength' AS event_output_len,
       LENGTH(COALESCE(s.context -> 'lastExecution' ->> 'output', '')) AS actual_output_len,
       t.payload ->> 'exempted'     AS exempted,
       t.payload ->> 'exemptReason' AS exempt_reason
FROM task_timeline t
JOIN sub_task s ON s.id = t.sub_task_id AND s.deleted = 0
WHERE t.deleted = 0
  AND t.event_type = 'delivery_gate_miss'
  AND LENGTH(COALESCE(s.context -> 'lastExecution' ->> 'output', '')) > 0
ORDER BY t.create_time DESC;

-- ────────────────────────────────────────────────
-- 5. 事件明细（抽查用途，可按需加 LIMIT）
-- ────────────────────────────────────────────────
SELECT id, sub_task_id, task_id, agent_id, create_time, payload
FROM task_timeline
WHERE deleted = 0
  AND event_type = 'delivery_gate_miss'
ORDER BY create_time DESC
LIMIT 50;