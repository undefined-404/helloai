-- ============================================================
-- V71__create_browser_session.sql
-- 用途：Browser 会话登记（N-003，Phase 2 C3-S1）
-- 背景：
--   Browser Agent = WEB_BROWSER 接入类型，复用现有 task/sub_task/调度/执行/产物链路
--   （C3 设计：不新增第二套任务调度；外部 AI 服务 + Playwright 桥接，平台推送/收产物）。
--   browser_session 只做【会话登记 + 展示/审计】：状态由执行结果回写，不持有浏览器实例。
-- 关键设计：
--   - 状态 BEGIN/ACTIVE/CLOSED/FAILED（登记语义，无状态机约束力）
--   - 不设引用约束/级联到 task/sub_task（反锁禁令：会话是派生态，绝不反向约束任务）
--   - current_url/last_screenshot_ref 供展示与审计
-- 参考：doc/design/HelloAI_Phase2_C3_BrowserAgent设计预研.md §3.2
-- ============================================================

CREATE TABLE IF NOT EXISTS browser_session (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    agent_id             BIGINT       NOT NULL,
    task_id              BIGINT,
    status               VARCHAR(16)  NOT NULL DEFAULT 'BEGIN',
    current_url          VARCHAR(512),
    last_screenshot_ref  VARCHAR(255),
    begin_time           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    close_time           TIMESTAMPTZ,
    create_by            VARCHAR(64)  NOT NULL DEFAULT '',
    update_by            VARCHAR(64)  NOT NULL DEFAULT '',
    create_time          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted              SMALLINT     NOT NULL DEFAULT 0,
    remark               VARCHAR(255),
    CONSTRAINT chk_browser_session_status CHECK (status IN ('BEGIN', 'ACTIVE', 'CLOSED', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_browser_session_agent
    ON browser_session(agent_id, status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_browser_session_task
    ON browser_session(task_id) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_browser_session_update_time ON browser_session;
CREATE TRIGGER update_browser_session_update_time BEFORE UPDATE ON browser_session
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE browser_session IS 'Browser 会话登记（N-003，C3 设计：登记/展示语义，不持有浏览器实例；状态由执行结果回写，不反锁 task/sub_task）';
COMMENT ON COLUMN browser_session.status IS '会话状态：BEGIN / ACTIVE / CLOSED / FAILED（登记语义，无状态机约束力）';
COMMENT ON COLUMN browser_session.current_url IS '最近浏览 URL（展示/审计）';
COMMENT ON COLUMN browser_session.last_screenshot_ref IS '最近截图 artifact 引用（展示/审计）';
