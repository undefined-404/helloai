-- ============================================================
-- V129__llm_balance_snapshot.sql
-- 用途：外部供应商余额快照（G-026 / Q-1）
--
-- 背景（`G-026`，计划 `doc/plan/HelloAI 额度与用量监控实施计划（2026-11-02）.md` §Q-1，
--   设计 `doc/design/HelloAI LLM 额度与用量监控设计.md` §4.4）：
--   平台同接 4 家外部供应商（deepseek / moonshot / minimax / 博查），余额分散在各自控制台。
--   本表是每次抓取的快照：回答「某家某时刻还有多少钱 / 剩余多少额度、失败没有、失败原因是什么」——
--   否则余额只存在于外部接口返回里，跑到哪一步 / 失败原因无从查询。
--
-- 关键设计：
--   - 只建这一张表（设计 §4.4 有意否决 llm_account / llm_usage_daily：
--     凭据事实在 credential_vault，账号表=同一事实两处写；token 已在 agent_event，
--     日聚合表与事件源双写必然漂移）。
--   - 金额型（deepseek/moonshot/博查）写 amount；额度型（MiniMax 百分比）写 quota_percent。
--     两个字段可空，取数语义在应用层 `BalanceSnapshot` 区分，不混用。
--   - raw_payload 存原始响应（JSONB），出问题时不用重新抓。
--   - 保留策略：只留最近 30 天快照，由定时任务清理（Q-6）；趋势按天取末条，不额外聚合。
--
-- 映射：BaseEntity 通用审计列 + `deleted`（与 platform_backup / chat_skill_run 同款）。
-- 幂等：CREATE TABLE IF NOT EXISTS + CREATE INDEX IF NOT EXISTS
-- 红线：V1~V128 只读，本迁移递增为 V129（Flyway 校验和不可变，CODE_STYLE §16）
-- 回滚：DROP TABLE llm_balance_snapshot;
-- ============================================================

CREATE TABLE IF NOT EXISTS llm_balance_snapshot (
    id            BIGINT       NOT NULL PRIMARY KEY,
    provider_key  VARCHAR(64)  NOT NULL,
    currency      VARCHAR(8),
    amount        NUMERIC(20, 2),
    quota_percent NUMERIC(5, 2),
    raw_payload   JSONB,
    fetched_at    TIMESTAMPTZ  NOT NULL,
    error         VARCHAR(512),
    create_by     VARCHAR(64)  NOT NULL DEFAULT '',
    update_by     VARCHAR(64)  NOT NULL DEFAULT '',
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT     NOT NULL DEFAULT 0,
    remark        VARCHAR(255)
);

-- 供应商维度查询（overview 聚合 + 库存清理按 provider_key + fetched_at 倒序取）
CREATE INDEX IF NOT EXISTS idx_llm_balance_snapshot_provider_time
    ON llm_balance_snapshot (provider_key, fetched_at DESC) WHERE deleted = 0;