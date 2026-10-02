-- V98__drop_task_running_spec_phase_b_tables.sql
-- B4（2026-10-02）：删除 Phase B 双轨残留的两张表（V36 建，V36 本身不改）
--
-- 背景：TaskRunningSpecService 曾有双实现（JSONB / 独立表），由
--   helloai.task-running-spec.storage 切换；该开关在**全部 yml 中零显式设置**，
--   且 table 分支为 matchIfMissing=false ⇒ Phase B **恒为死代码**（255 行 + 两张表）。
--   按「回退旧链的功能不需要 / 硬切即为单向门」决策删除 Java 侧实现后，
--   V36 建的 task_running_spec / task_execution_record 已无任何写入方，一并落表清理。
--
-- 注意：V36（建表）与 V37（补 deleted 列）都是**已应用**的迁移，不可回改
--   触发器 update_task_running_spec_update_time / update_task_execution_record_update_time
--   随表级联删除，无需单独 DROP TRIGGER。
--   自研项目、无生产数据 ⇒ 不做数据保全；如需回退请 git revert 本文件并重建 V36。

DROP TABLE IF EXISTS task_execution_record;
DROP TABLE IF EXISTS task_running_spec;
