-- ============================================================
-- V114__task_add_version.sql
-- 用途：task 表增加乐观锁 version 列，使 Task 实体可使用 MyBatis-Plus @Version
-- 背景：
--   Task 此前缺 @Version（仅 SubTask / AgentExecutionRecord 有），而 TaskServiceImpl 的
--   updateStatus / updatePriority / 配置更新 / 重新发布 四处均为「getById 载入 → 改字段
--   → updateById(整实体)」。缺乐观锁时，若两次读改写交错，后写者会用**陈旧的整行快照**
--   覆盖前者刚写入的字段 —— 即丢失更新（lost update），且全过程无任何报错。
--   本迁移为 task 增加 version 列，使 updateById 由 OptimisticLockerInnerInterceptor
--   自动完成 version 比较与自增（规范 §15：禁止手写 version = version + 1）。
-- 契约纪律：
--   version 是**持久层并发控制字段**，按 CODE_STYLE §7.2 / W10 **不得进入跨域契约**
--   （TaskView / TaskDraft / PlannerAgentRef 等投影一律不携带），避免消费方被迫复现 CAS 语义。
-- 兼容性：
--   DEFAULT 0 + NOT NULL ⇒ 存量行自动取 0；未载入 version 的内存实体（version = null）不会
--   触发 CAS 条件，行为与迁移前一致（故对「新建后立即 updateById」的路径零影响）。
-- ============================================================

ALTER TABLE task
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN task.version IS '乐观锁版本号（MyBatis-Plus @Version，拦截器自动维护，禁止手写自增 SQL）';

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V114] task.version 列已就绪（乐观锁，与 SubTask / AgentExecutionRecord 同口径）';
END $$;
