-- ============================================================
-- V30__remove_patrol_role.sql
-- 用途：移除 PATROL 巡检角色 —— Agent 角色收敛为 PLANNER/EXECUTOR/REVIEWER 三角色
-- 背景：
--   PATROL 角色自项目初始化以来从未投入实际使用（代码中无任何按 PATROL
--   分支的调度逻辑，patrol_record 表始终 0 行，PATROL 角色 Agent 0 个）。
--   其设计目标（异常任务巡检兜底）已由更可靠的机制覆盖：
--   1. 重分配熔断（V24 reassign_attempt_count）；
--   2. 死信池人工兜底（V25 DEAD_LETTER）；
--   3. 定时补偿任务（Outbox 补偿 / 超时巡检 / 执行记录补偿）。
--   本迁移与后端同轮删除同步：AgentRole 枚举、patrol MQ 队列/绑定、
--   PatrolRecord 实体/Mapper/Service、级联删除链、patrolCount 统计均已移除。
-- 机制：
--   - 重建 agent / task_timeline 两个角色 CHECK 约束（去掉 PATROL）
--   - 物理删除 PATROL 角色提示词模板种子行（id=2000000000000000004）
--   - DROP patrol_record 表（含其索引与外键，0 行数据，无需迁移）
-- 前置校验：执行前已确认 agent/patrol_record/prompt_template/task_timeline
--   中 PATROL 相关行数均为 0（2026-07-30 生产库核验）。
-- ============================================================

-- 1. agent.role CHECK 约束收敛为三角色
ALTER TABLE agent
    DROP CONSTRAINT IF EXISTS chk_agent_role;

ALTER TABLE agent
    ADD CONSTRAINT chk_agent_role CHECK (role IN ('PLANNER', 'EXECUTOR', 'REVIEWER'));

COMMENT ON COLUMN agent.role IS '智能体角色：PLANNER/EXECUTOR/REVIEWER';

-- 2. task_timeline.role CHECK 约束去掉 PATROL（保留 SYSTEM 系统角色）
ALTER TABLE task_timeline
    DROP CONSTRAINT IF EXISTS chk_task_timeline_role;

ALTER TABLE task_timeline
    ADD CONSTRAINT chk_task_timeline_role CHECK (role IN ('PLANNER', 'EXECUTOR', 'REVIEWER', 'SYSTEM'));

COMMENT ON COLUMN task_timeline.role IS '事件角色: PLANNER/EXECUTOR/REVIEWER/SYSTEM';

-- 3. 删除 PATROL 角色提示词模板种子行（物理删除，绕过逻辑删除标记）
DELETE FROM prompt_template WHERE id = 2000000000000000004 AND role = 'PATROL';

COMMENT ON COLUMN prompt_template.role IS '角色：PLANNER/EXECUTOR/REVIEWER';

-- 4. 删除巡查记录表（0 行数据，级联删除链已在代码侧同步移除）
DROP TABLE IF EXISTS patrol_record;

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V30] PATROL 角色已移除：CHECK 约束收敛为三角色，patrol_record 表已删除';
END $$;
