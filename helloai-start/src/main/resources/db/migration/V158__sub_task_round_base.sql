-- V158：附件轮次与可重置计数器解耦（`G-054`）
--
-- 问题：`V157` 的 round = sub_task.rework_count + 1。但 rework_count 有两条**清零**路径
-- （`reworkFresh` 人工驳回 / `redispatchDeadLetter` 死信重派），清零是为了重置返工与重试
-- **预算**（与 attempt_total 对称），跟「第几轮」不是同一件事，却共用同一计数器。
-- 后果：清零后轮次回到 1，新旧两轮产物同为 round=1，轮次彻底失去区分能力。
-- 真机实证：子任务 2125050135475167234 经 3 次死信重派产 5 份产物，round 全为 1。
--
-- 修法：轮次 = round_base + rework_count + 1。
--   * round_base 是「已被重置消耗掉的轮次基数」，**只在两条清零路径累加**，
--     累加量 = 当时的 (round_base + rework_count + 1)，于是清零当刻恰好翻到下一轮；
--   * rework_count 保持原语义（返工预算），该清还是清；
--   * 两者相加才是「第几次执行」，单源推导在 `SubTaskRound.currentOf`。
-- **不改 rework_count 的清零**——那会破坏返工预算语义（判据 4：同一字段不能兼两义）。
ALTER TABLE sub_task ADD COLUMN IF NOT EXISTS round_base INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN sub_task.round_base IS
    '已被重置消耗掉的轮次基数（G-054）。当前轮 = round_base + rework_count + 1；仅 reworkFresh / redispatchDeadLetter 在清零 rework_count 时累加，使清零当刻翻到下一轮。';

-- 回填一：历史 round 按**执行锚点**重算。
--
-- V157 当时只有一类可考（ACTIVE 行 = 当前轮），INACTIVE/DELETED 一律留在 1；但由于
-- rework_count 会被清零，连 ACTIVE 那一类也判错了。真正的轮次锚点是
-- `sub_task_execute_start`——它是「本次执行真的开跑了」的唯一事件，且每次执行必发一次
-- （`LocalExecutionCommandConsumer` 消费命令后立刻记录，与物化同一条事务链之后）。
-- 因此：附件属于第几轮 = 该子任务在它创建时刻之前发生过几次 execute_start。
--
-- 幂等：本式是纯函数（只依赖 timeline 与 create_time），重跑结果相同。
UPDATE attachment a
   SET round = GREATEST(1, (
        SELECT count(*) FROM task_timeline t
         WHERE t.sub_task_id = a.sub_task_id
           AND t.event_type = 'sub_task_execute_start'
           AND t.create_time <= a.create_time))
 WHERE a.deleted = 0
   AND EXISTS (SELECT 1 FROM task_timeline t2
                WHERE t2.sub_task_id = a.sub_task_id
                  AND t2.event_type = 'sub_task_execute_start');

-- 回填二：round_base 抬到「历史已用到的最远轮次」，保证下一份产物不会与旧轮撞号。
--   currentRound() = round_base + rework_count + 1 必须 >= max(历史 round)
--   ⇒ round_base = max(0, max(round) - rework_count - 1)
-- 无附件的子任务保持 0（首轮尚未产出）。
--
-- 本式对两类子任务都正确：
--   * 有 execute_start 的：max(round) 由回填一重算而来，base 与之一致；
--   * 无 execute_start 但有附件的（V157 回填时把 ACTIVE 行标成了 rework_count+1）：
--     max(round) 就是那个值，算出的 base 让 currentRound() 恰好等于它，
--     ACTIVE 附件不会被误报成「上一轮旧证据」。已按真机数据核对（子任务
--     2125081636925435906：rework_count=1、max(round)=2 ⇒ base=0 ⇒ 当前轮 2，与 ACTIVE 一致）。
UPDATE sub_task s
   SET round_base = GREATEST(0, m.max_round - COALESCE(s.rework_count, 0) - 1)
  FROM (SELECT sub_task_id, max(round) AS max_round
          FROM attachment
         WHERE deleted = 0
         GROUP BY sub_task_id) m
 WHERE s.id = m.sub_task_id
   AND s.deleted = 0;

-- 暂不建索引：`round` / `round_base` 当前都只在已按主键取到的单行上使用，无范围扫描。