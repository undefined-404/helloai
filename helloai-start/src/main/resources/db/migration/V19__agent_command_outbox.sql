-- ============================================================
-- V19: 执行命令 Outbox 表 agent_command_outbox（②a 最小闭环专用）
-- ------------------------------------------------------------
-- 背景：Phase 2H（差距表 N1）执行命令 → Outbox → MQ → Consumer 闭环。
--   - ExecutionCommandService 在 dispatch-mode ∈ {MQ, BOTH} 时同事务写入本表；
--   - helloai-job OutboxRelayTask 周期扫描 PENDING + 重试节奏，
--     反序列化 payload 调底层 ExecutionCommandMqPublisher.doPublish()；
--   - Publisher 实际投递结果只回写本表（markSent / markFailed），不污染
--     agent_execution_record（执行生命周期）与 task_timeline（业务级时间线）。
--
-- 设计要点：
--   1) 与现有 agent_outbox_event（SubTask 状态变更事件）严格分离——
--      避免 OutboxRelay 误扫到 status change outbox 行 payload/routingKey 不匹配；
--   2) aggregate_type 字段写死 'EXECUTION_COMMAND'，未来真做统一 outbox 时
--      INSERT SELECT 到 agent_outbox_event 即可合并；本轮不引入通用 5 态，
--      仅保留 PENDING / SENT / FAILED 三态；状态机扩展 (CONFIRMED 等) 留给 ②b；
--   3) aggregate_id = agent_execution_record.id，做业务聚合根追溯用；
--   4) payload 是 ExecutionCommandMqMessage 的 JSON 序列化形式
--      （与 MqExecutionCommandConsumer 消费端 readValue(byte[]) 对称）。
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_command_outbox (
    id              BIGINT         NOT NULL PRIMARY KEY,
    event_id        VARCHAR(128)   NOT NULL,
    aggregate_type  VARCHAR(64)    NOT NULL DEFAULT 'EXECUTION_COMMAND',
    aggregate_id    VARCHAR(128)   NOT NULL,
    payload         JSONB          NOT NULL,
    status          VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    retry_count     INT            NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_msg       VARCHAR(1024),
    deleted         SMALLINT       NOT NULL DEFAULT 0,
    create_by       VARCHAR(64)    NOT NULL DEFAULT 'system',
    update_by       VARCHAR(64)    NOT NULL DEFAULT 'system',
    create_time     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark          VARCHAR(255)
);

-- 同 eventId 全局唯一：业务侧 recordId 由 ExecutionCommandService 自带雪花，
-- 这里采用 eventId 作为防重键，确保异步入库 / 重投重发不会重复推进。
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_command_outbox_event_id
    ON agent_command_outbox(event_id);

-- 主扫描路径：PENDING + 已到 next_retry_at + 未软删；partial index 减小扫描面。
CREATE INDEX IF NOT EXISTS idx_agent_command_outbox_pending_scan
    ON agent_command_outbox(next_retry_at)
    WHERE status = 'PENDING' AND deleted = 0;

-- 投递历史 / 审计：按状态 + 创建时间范围扫描。
CREATE INDEX IF NOT EXISTS idx_agent_command_outbox_status_create
    ON agent_command_outbox(status, create_time)
    WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_agent_command_outbox_update_time ON agent_command_outbox;
CREATE TRIGGER update_agent_command_outbox_update_time
    BEFORE UPDATE ON agent_command_outbox
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE agent_command_outbox IS '执行命令 Outbox 表（②a 最小闭环专用，独立于 agent_outbox_event）';
COMMENT ON COLUMN agent_command_outbox.id IS '主键ID（Snowflake）';
COMMENT ON COLUMN agent_command_outbox.event_id IS '消息唯一标识（ExecutionCommand.eventId）';
COMMENT ON COLUMN agent_command_outbox.aggregate_type IS '业务聚合类型：本表固定 EXECUTION_COMMAND，未来合并到 agent_outbox_event';
COMMENT ON COLUMN agent_command_outbox.aggregate_id IS '业务聚合根 ID（agent_execution_record.id）';
COMMENT ON COLUMN agent_command_outbox.payload IS '序列化后的 ExecutionCommandMqMessage，与 MqExecutionCommandConsumer 消费端对称';
COMMENT ON COLUMN agent_command_outbox.status IS '投递状态：PENDING / SENT / FAILED';
COMMENT ON COLUMN agent_command_outbox.retry_count IS '已重试次数';
COMMENT ON COLUMN agent_command_outbox.next_retry_at IS '下一次可重试时间（指数退避）';
COMMENT ON COLUMN agent_command_outbox.error_msg IS '最后一次失败原因';
