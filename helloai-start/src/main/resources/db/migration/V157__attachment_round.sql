-- V157：附件轮次快照（`VU-3` 第 4 条）
--
-- 目的：驳回返工后旧轮产物不再「凭空消失」，而是「归档到第 N 轮仍可读出」。
-- 语义分家（这是本列存在的全部理由）：
--   * status 管「能否被核验/依赖装载读到」——`invalidateBySubTask` 驳回时原位置 INACTIVE，语义不变；
--   * round  管「属于第几轮」——登记时点快照，此后不随返工次数变化而改写。
-- 把两件事压在一个字段上，正是「旧轮产物在审计面彻底消失」的成因。
ALTER TABLE attachment ADD COLUMN IF NOT EXISTS round INTEGER NOT NULL DEFAULT 1;

COMMENT ON COLUMN attachment.round IS
    '登记时点轮次快照（= 登记时 sub_task.rework_count + 1，首轮 1）。status 管「能否读到」，round 管「属于第几轮」，两者并存不互替。';

-- 回填只有一类可考：ACTIVE 行必为当前轮（驳回即整体失活 ⇒ 现存 ACTIVE 只可能是本轮登记）。
-- INACTIVE / DELETED 历史行**不可考**：按当前 rework_count 反推会把首轮产物标成「第 N 轮」，
-- 比保留默认 1 更错，故一律留在 1 并以此注释为准。
-- 幂等守卫：`a.round = 1` 保证重跑不会把已回填的行再次叠加。
UPDATE attachment a
   SET round = COALESCE(s.rework_count, 0) + 1
  FROM sub_task s
 WHERE a.sub_task_id = s.id
   AND a.status::text = 'ACTIVE'
   AND a.round = 1
   AND COALESCE(s.rework_count, 0) > 0;

-- 暂不建 (sub_task_id, round) 索引：当前无按 round 过滤的查询，预留索引即「无读取方的索引」；
-- 保留期清理（`VU-3` 第 3 条）落地时按实际 WHERE 条件补。
