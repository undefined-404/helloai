-- ══════════════════════════════════════════════════════════════════════════
-- V152：子任务维度技能执行台账 sub_task_skill_run（MA-3）
--
-- 目的：让「每一次技能执行」（成功 / 非零退出 / 超时 / SDK 异常）在库里留一行，
--       且带 sub_task_id 可 join —— 这是 SK-1（技能效果报表）缺的那个键。
--
-- 为什么不复用 chat_skill_run：它在 planner 澄清域，语义是「用户在对话里点了一个技能」；
--   往上加 sub_task_id 就是「一列两义」（同一列在不同行代表两种事实）。
-- 为什么不复用 SandboxCallLedger：那是进程内内存台账（重启即清空），服务的是
--   「本子任务内的引用证据」；本表是跨会话的统计事实。两者并存、互不替代。
--
-- ★ 已写入方在 finally 里落库（best-effort + REQUIRES_NEW），校验与回归全过之后
--   才由本迁移接管。字段与索引形状一旦发布，改它就要走 DELETE flyway_schema_history
--   那一套，故此处一次写全。
--
-- 回滚：DROP TABLE IF EXISTS sub_task_skill_run;
-- ══════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS sub_task_skill_run (
    id             BIGINT       NOT NULL PRIMARY KEY,
    sub_task_id    BIGINT       NOT NULL,
    task_id        BIGINT,
    agent_id       BIGINT,
    round          INT          NOT NULL DEFAULT 0,
    skill_name     VARCHAR(128) NOT NULL,
    skill_version  VARCHAR(64),
    command_prefix VARCHAR(200),
    exit_code      INT          NOT NULL,
    truncated      SMALLINT     NOT NULL DEFAULT 0,
    duration_ms    BIGINT,
    call_id        VARCHAR(64),
    create_by      VARCHAR(64)  NOT NULL DEFAULT '',
    update_by      VARCHAR(64)  NOT NULL DEFAULT '',
    create_time    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark         VARCHAR(255),
    deleted        SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE sub_task_skill_run IS '子任务维度技能执行台账（MA-3；每次技能执行一行，含失败）';
COMMENT ON COLUMN sub_task_skill_run.sub_task_id IS '所属子任务 ID（唯一必需身份维度；报表按它 join）';
COMMENT ON COLUMN sub_task_skill_run.task_id IS '所属任务 ID（★冗余列：可从 sub_task_id join 得到，刻意反范式换取报表聚合时少一次大表 join；写入时从同一份事实取，不二次查询）';
COMMENT ON COLUMN sub_task_skill_run.agent_id IS '执行者 Agent ID（MA-3 之前该字段在调用链上被丢弃）';
COMMENT ON COLUMN sub_task_skill_run.round IS '执行轮次（与 SandboxCallLedger.round 同源；无轮次语义传 0）';
COMMENT ON COLUMN sub_task_skill_run.skill_name IS '技能包名（容器内 /workspace/skills/<name>）';
COMMENT ON COLUMN sub_task_skill_run.skill_version IS '技能包版本目录名';
COMMENT ON COLUMN sub_task_skill_run.command_prefix IS '命令前 200 字符（★不是命令指纹：差异可能落在截断之后，判重须用 exit_code + skill_name + round）';
COMMENT ON COLUMN sub_task_skill_run.exit_code IS '真实退出码；124=超时，-1=SDK 异常（结构化失败，非命令退出码）';
COMMENT ON COLUMN sub_task_skill_run.truncated IS '输出是否被截断（0/1）；1 时「成功但结果不完整」，统计须与完整成功分开';
COMMENT ON COLUMN sub_task_skill_run.duration_ms IS '端到端耗时（毫秒，含建容器 / 注入 / 执行）';
COMMENT ON COLUMN sub_task_skill_run.call_id IS '沙箱调用台账条目 ID（与 SandboxCallLedger 互查；未登记时为空）';

-- 主要查询：按子任务列最近 N 条（时间倒序）
CREATE INDEX IF NOT EXISTS idx_stsr_sub_task_id
    ON sub_task_skill_run(sub_task_id, create_time DESC);

-- 报表入口：按任务 + 技能聚合成功/失败
CREATE INDEX IF NOT EXISTS idx_stsr_task_id
    ON sub_task_skill_run(task_id, skill_name);
