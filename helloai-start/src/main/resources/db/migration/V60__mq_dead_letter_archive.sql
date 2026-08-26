-- =========================================================
-- V60：死信台账表 mq_dead_letter_archive
--   背景：Step 4（v1.2 分布式健壮性改造）新增 DlxAlertConsumer，
--   消费 dlxQueue 死信时「先落台账再 ACK」——死信消息 ACK 后即从队列消失，
--   台账是唯一可追溯数据源（失败可追溯、可重放）。
--   列名与 RabbitMQ 死信 header x-first-death-* 一一对应。
-- =========================================================

CREATE TABLE IF NOT EXISTS mq_dead_letter_archive (
    id                    BIGSERIAL PRIMARY KEY,
    original_exchange     VARCHAR(255),
    original_routing_key  VARCHAR(255),
    first_death_exchange  VARCHAR(255),
    first_death_queue     VARCHAR(255),
    first_death_reason    VARCHAR(255),
    headers               JSONB,
    body                  TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at           TIMESTAMPTZ          -- 重放预留：重放工具回填，本轮不实现
);

-- 时间范围扫描（告警核验 / 后续重放工具按时间窗口捞数据）。
CREATE INDEX IF NOT EXISTS idx_mq_dead_letter_archive_created_at
    ON mq_dead_letter_archive (created_at);

COMMENT ON TABLE mq_dead_letter_archive IS 'MQ 死信台账（DlxAlertConsumer 先落库再 ACK，重放预留 replayed_at）';
COMMENT ON COLUMN mq_dead_letter_archive.id IS '主键（BIGSERIAL 自增）';
COMMENT ON COLUMN mq_dead_letter_archive.original_exchange IS '死信原始发布 exchange';
COMMENT ON COLUMN mq_dead_letter_archive.original_routing_key IS '死信原始 routing key（重放工具按此重发）';
COMMENT ON COLUMN mq_dead_letter_archive.first_death_exchange IS 'x-first-death-exchange 头';
COMMENT ON COLUMN mq_dead_letter_archive.first_death_queue IS 'x-first-death-queue 头';
COMMENT ON COLUMN mq_dead_letter_archive.first_death_reason IS 'x-first-death-reason 头（如 rejected / expired）';
COMMENT ON COLUMN mq_dead_letter_archive.headers IS '完整消息头快照（JSONB）';
COMMENT ON COLUMN mq_dead_letter_archive.body IS '消息体快照（超长截断至 64KB）';
COMMENT ON COLUMN mq_dead_letter_archive.created_at IS '第一次进入死信台账的时间';
COMMENT ON COLUMN mq_dead_letter_archive.replayed_at IS '重放回填时间（预留，本轮不实现重放工具）';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'mq_dead_letter_archive') THEN
        RAISE NOTICE 'V60 OK: mq_dead_letter_archive 已就位';
    ELSE
        RAISE EXCEPTION 'V60 FAIL: mq_dead_letter_archive 缺失';
    END IF;
END $$;