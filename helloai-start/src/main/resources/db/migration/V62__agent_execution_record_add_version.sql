-- ============================================================
-- V62__agent_execution_record_add_version.sql
-- 用途：AgentExecutionRecord 增加乐观锁 version 字段（Phase 0 A2.1）
-- 背景：
--   markRunning / markSuccess / markFailed / markTimeout 当前使用
--   lambdaUpdate().eq(status, oldStatus) 双条件更新，但 lambdaUpdate 链式
--   更新不触发 MyBatis-Plus OptimisticLockerInnerInterceptor（规范 §15），
--   无法享受 @Version 自动 CAS。本迁移为 agent_execution_record 增加
--   version 列，使 mark* 系列可切换为 update(entity, wrapper) 形式，
--   由拦截器自动完成 version 比较与自增（禁止手写 version = version + 1）。
-- 参考：doc/design/HelloAI_Phase0_架构改造执行方案.md Epic-A A2.1
-- ============================================================

ALTER TABLE agent_execution_record
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN agent_execution_record.version IS '乐观锁版本号（MyBatis-Plus @Version，拦截器自动维护，禁止手写自增 SQL）';

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V62] agent_execution_record.version 列已就绪';
END $$;