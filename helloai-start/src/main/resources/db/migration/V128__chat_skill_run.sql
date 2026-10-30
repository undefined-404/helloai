-- V128__chat_skill_run.sql
-- 目的：SKX-13 技能执行异步化 —— 对话内技能执行的「运行记录」（提交即返回句柄 + 后台执行 + 进度可查）。
-- 计划：doc/plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md §SKX-13
-- 决策：D-2026-10-30-1（执行载体 = 独立线程池；状态载体 = 本表；前端 = 轮询）
--
-- 为什么新建表而不是给 chat_attachment / requirement_message 加列：
--   1) 语义锚点不同 —— 一次「技能执行」是运行时作业，不是会话内容；
--   2) 生命周期不同 —— 会话删除后附件随之回收，但运行记录要留着排障（尤其失败的）；
--   3) 状态机不同构 —— 附件解析是 PENDING/PARSING/READY/FAILED/SKIPPED，
--      技能执行是 PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED，
--      混在一张表会出现「同一列两种含义」的判据漂移。
--
-- 幂等闸门：status 的条件更新（PENDING → RUNNING）是唯一的领取凭据，
-- 重复投递（重试 / worker 重跑）不会跑第二次。
--
-- 并发互斥：uk_skill_run_active 用部分唯一索引在**数据库层**兜住「同会话同时只有一个 RUNNING」，
-- 应用层的预检查只是为了给出可读报错，索引才是最终保证 ——
-- 沙箱槽按会话复用（槽键 = -conversationId），并发两次会互相踩工作目录与产物路径。
--
-- 回滚：DROP TABLE chat_skill_run;

CREATE TABLE chat_skill_run (
    id              bigint       PRIMARY KEY,
    conversation_id bigint       NOT NULL,
    -- 发起者用户 id（归属与权限校验用）
    user_id         bigint,
    -- 技能包名（如 archify）
    skill_name      varchar(64)  NOT NULL,
    -- 触发方式：CHAT（对话内选技能）/ EDIT（编辑消息后重发）
    trigger_source  varchar(16)  NOT NULL DEFAULT 'CHAT',
    -- 状态机：PENDING → RUNNING → SUCCEEDED / FAILED / CANCELLED
    status          varchar(16)  NOT NULL DEFAULT 'PENDING',
    -- 阶段（进度可见性）：SPEC / RENDER / CHECK / EXPORT / DONE
    phase           varchar(16),
    -- 阶段的可读说明（给前端展示，如「正在生成图表输入」）
    phase_detail    varchar(255),
    -- 失败原因摘要（用户可见级别，不含堆栈）
    error_message   varchar(1000),
    -- 产物附件 id（成功后）
    attachment_id   bigint,
    -- 产物消息 payload（成功后，与对话里那条 assistant 消息的 payload 同口径）
    result_payload  text,
    -- 服务端硬超时上限（秒）；到点仍未结束则判 FAILED
    timeout_seconds integer      NOT NULL DEFAULT 600,
    started_at      timestamptz,
    finished_at     timestamptz,
    deleted         smallint     NOT NULL DEFAULT 0,
    create_by       varchar(64)  NOT NULL DEFAULT 'system',
    update_by       varchar(64)  NOT NULL DEFAULT 'system',
    create_time     timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark          varchar(255)
);

COMMENT ON TABLE chat_skill_run IS '对话内技能执行运行记录（SKX-13 异步化状态载体）';
COMMENT ON COLUMN chat_skill_run.trigger_source IS '触发方式：CHAT=对话内选技能，EDIT=编辑消息后重发';
COMMENT ON COLUMN chat_skill_run.status IS 'PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED';
COMMENT ON COLUMN chat_skill_run.phase IS '执行阶段：SPEC=生成输入 / RENDER=渲染 / CHECK=自检 / EXPORT=回读导出 / DONE=终态';
COMMENT ON COLUMN chat_skill_run.error_message IS '失败原因摘要（用户可见级别）';

-- 会话维度查询（前端轮询按 runId 单查，这里给「会话内最近一次执行」用）
CREATE INDEX idx_skill_run_conv ON chat_skill_run (conversation_id) WHERE deleted = 0;

-- 同会话互斥兜底：数据库层保证「同一会话同时最多一个 RUNNING」
CREATE UNIQUE INDEX uk_skill_run_active ON chat_skill_run (conversation_id)
    WHERE status = 'RUNNING' AND deleted = 0;
