-- ============================================================
-- V66__create_agent_session.sql
-- 用途：Agent 执行会话表（Phase 1 Step 3，Session Manager / N-007 收口）
-- 背景：
--   N-007 执行恢复缺口：执行快照 / 恢复上下文 / 中断点 / 可恢复执行 /
--   恢复幂等策略。架构参考 §4.5「执行进度 / 恢复快照显式化」+ 长期思路
--   P0-1 AgentRuntime SessionManager 成员落位。
--   受限于 LLM 调用不可中断（一次同步调用），本轮「可恢复执行」= 记录
--   中断点 + 供重派后恢复上下文 + 幂等防重入，不做 LLM 级断点续接。
-- 关键设计：
--   - 粒度 = Turn 级（一次执行尝试一行），对齐 ADR-001 Turn 语义；
--     与 agent_execution_record（命令生命周期）互补，不替代。
--   - status：ACTIVE（执行中）/ COMPLETED（成功）/ FAILED（失败）/
--     TIMEOUT（超时）/ ABORTED（回收/重派中断）
--   - snapshot JSONB：恢复上下文快照（skills/tools/depCount 等装配事实）
--   - 中断点 = 最新 ACTIVE 会话的 turn/step；回收路径读它落 timeline
--     sub_task_session_interrupted 并把 ACTIVE 置 ABORTED（幂等防重入）
--   - 无唯一约束：turn 在 reworkFresh/死信重派清零计数器后可能复用，
--     写入采用「同 subTaskId+turn 已存在 ACTIVE 则更新」的幂等语义；
--     读取一律按 create_time DESC, id DESC 取最新
-- 参考：doc/design/HelloAI_Phase1_Harness吸收执行方案.md §1.1/§6 Step 3；
--       doc/design/adr/ADR-001-run-turn-step-model.md（Turn 语义）
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_session (
    id              BIGINT      NOT NULL PRIMARY KEY,
    run_id          VARCHAR(64) NOT NULL,
    task_id         BIGINT      NOT NULL,
    sub_task_id     BIGINT      NOT NULL,
    agent_id        BIGINT      NOT NULL,
    turn            INT         NOT NULL DEFAULT 1,
    step            INT         NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL,
    snapshot        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    error           VARCHAR(500),
    create_by       VARCHAR(64) NOT NULL DEFAULT '',
    update_by       VARCHAR(64) NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT    NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_agent_session_sub_task ON agent_session(sub_task_id, deleted, id);
DROP TRIGGER IF EXISTS update_agent_session_update_time ON agent_session;
CREATE TRIGGER update_agent_session_update_time BEFORE UPDATE ON agent_session
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE agent_session IS 'Agent 执行会话表（Phase 1 Step 3，执行快照/中断点/恢复上下文载体）';
COMMENT ON COLUMN agent_session.run_id IS 'Run 标识（run-{taskId}-{roundNum}，见 ADR-001）';
COMMENT ON COLUMN agent_session.turn IS '执行尝试序号（ADR-001 Turn；reworkFresh/死信重派清零计数器后可能复用）';
COMMENT ON COLUMN agent_session.step IS '中断点（最近执行到哪：2=上下文装配完成/LLM 前，4=LLM 完成）';
COMMENT ON COLUMN agent_session.status IS '会话状态：ACTIVE/COMPLETED/FAILED/TIMEOUT/ABORTED';
COMMENT ON COLUMN agent_session.snapshot IS '恢复上下文快照（skills/tools/depCount 等装配事实 JSONB）';
COMMENT ON COLUMN agent_session.error IS '失败/中断原因摘要（截断 500 字符）';
