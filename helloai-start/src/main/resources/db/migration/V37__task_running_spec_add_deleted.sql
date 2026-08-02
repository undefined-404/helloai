-- V37__task_running_spec_add_deleted.sql
-- Phase B 实体继承 BaseEntity 后，MyBatis-Plus 全局配置（logic-delete-field: deleted,
-- logic-not-delete-value: 0）会在所有 SELECT/UPDATE/DELETE 上追加 `WHERE deleted=0` 条件；
-- V36 建表时未声明 deleted 列，启动期 TaskRunningSpecMapper.selectCount 抛
--   BadSqlGrammarException: column "deleted" does not exist
-- 导致 Phase B 整个 ApplicationContext 启动失败。
-- 本迁移为两张 Phase B 新表补齐 deleted 列，与 task / sub_task 等既有表一致：
--   smallint NOT NULL DEFAULT 0，无索引（任务下唯一索引已覆盖常用过滤）。

ALTER TABLE task_running_spec
    ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE task_execution_record
    ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0;
