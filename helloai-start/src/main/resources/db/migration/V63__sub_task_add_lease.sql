-- ============================================================
-- V63__sub_task_add_lease.sql
-- 用途：子任务执行租约字段（Phase 0 A2.2）
-- 背景：
--   Agent 崩溃 / 宕机时，IN_PROGRESS 子任务没有"谁在执行、租约何时到期"
--   的记录，只能等既有超时巡检缓慢回收。本迁移为 sub_task 增加
--   owner + lease_until：
--     owner        当前执行 Worker 节点标识（与 agent_execution_record.worker_node
--                  同源，由 HostNameUtils.getHostName() 写入）
--     lease_until  租约到期时间；由 WatchdogLeaseRenewTask 周期续期，
--                  由 LeaseReconcilerTask 扫描回收（见执行方案 A2.3 / A2.4）
--   存量数据 owner / lease_until 均为 NULL，继续走既有超时巡检路径，不受影响。
-- 参考：doc/design/HelloAI_Phase0_架构改造执行方案.md Epic-A A2.2 ~ A2.4
-- ============================================================

ALTER TABLE sub_task
    ADD COLUMN IF NOT EXISTS owner VARCHAR(128),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

COMMENT ON COLUMN sub_task.owner IS '当前执行 Worker 节点标识（与 agent_execution_record.worker_node 同源）；NULL=未持有租约';
COMMENT ON COLUMN sub_task.lease_until IS '执行租约到期时间（Watchdog 续期 / Reconciler 回收依据）；NULL=未持有租约';

-- 租约回收扫描索引：只扫 IN_PROGRESS 且持有租约的行，按到期时间升序
CREATE INDEX IF NOT EXISTS idx_sub_task_lease_expiry
    ON sub_task(lease_until, create_time)
    WHERE status = 'IN_PROGRESS' AND owner IS NOT NULL;

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V63] sub_task.owner / lease_until 列已就绪';
END $$;