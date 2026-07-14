-- ============================================================
-- V18: 事件消费幂等日志表 event_consumption_log
-- ------------------------------------------------------------
-- 背景：Phase 2E/2F 引入 RabbitMQ 主链路后，
--   - AbstractIdempotentConsumer.tryConsumeBasic / tryConsumeEnhanced
--   - MessageDeduplicationService.isDuplicate / markConsumed / markFailed
-- 均依赖 event_consumption_log 表做 DB 层幂等兜底（Redis 仅做快路径缓存）。
--
-- 但 V1 初始化时未建表，且后续 migration 也未补，导致 DB 幂等层实际未生效，
-- 当前实际只剩 Redis 一层去重。E2E 阶段抓到此回归（MQ Consumer 触发
-- isDuplicate 时报 BadSqlGrammarException，relation does not exist）。
--
-- 本迁移补建该表，并：
--   1) PK 使用 Snowflake ID；
--   2) (message_id, consumer) 唯一，避免同一消费者重复写入；
--   3) deleted 软删除字段，配合 AbstractIdempotentConsumer 的 WHERE deleted=0；
--   4) status: CONSUMED / FAILED，便于事后审计；
--   5) 索引：message_id 单查快路径；create_time 范围扫描。
-- ============================================================

CREATE TABLE IF NOT EXISTS event_consumption_log (
    id              BIGINT       NOT NULL PRIMARY KEY,
    message_id      VARCHAR(128) NOT NULL,
    consumer        VARCHAR(128) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'CONSUMED',
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    create_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark          VARCHAR(255)
);

-- 同一消费者对同一条消息只允许一条记录，重复 markConsumed 走 ON CONFLICT DO NOTHING。
CREATE UNIQUE INDEX IF NOT EXISTS uk_event_consumption_log_msg_consumer
    ON event_consumption_log(message_id, consumer);

-- 幂等查询走单查快路径。
CREATE INDEX IF NOT EXISTS idx_event_consumption_log_msg
    ON event_consumption_log(message_id);

-- 时间范围扫描（如清理任务 / 审计）。
CREATE INDEX IF NOT EXISTS idx_event_consumption_log_create_time
    ON event_consumption_log(create_time);

DROP TRIGGER IF EXISTS update_event_consumption_log_update_time ON event_consumption_log;
CREATE TRIGGER update_event_consumption_log_update_time
    BEFORE UPDATE ON event_consumption_log
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE event_consumption_log IS '事件消费幂等日志（Redis 未命中时的 DB 兜底）';
COMMENT ON COLUMN event_consumption_log.id IS '主键ID（Snowflake）';
COMMENT ON COLUMN event_consumption_log.message_id IS '消息唯一标识（MQ eventId 或本系统 eventId）';
COMMENT ON COLUMN event_consumption_log.consumer IS '消费者名称（如 MqExecutionCommandConsumer）';
COMMENT ON COLUMN event_consumption_log.status IS '消费结果：CONSUMED / FAILED';
COMMENT ON COLUMN event_consumption_log.deleted IS '软删除标记（0=有效）';