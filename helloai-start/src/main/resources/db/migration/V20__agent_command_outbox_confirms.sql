DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'agent_command_outbox'
          AND column_name = 'status'
          AND data_type = 'character varying'
    ) THEN
        ALTER TABLE agent_command_outbox
            ALTER COLUMN status DROP DEFAULT;
        ALTER TABLE agent_command_outbox
            ALTER COLUMN status TYPE SMALLINT
            USING (
                CASE
                    WHEN status IN ('PENDING', '0') THEN 0
                    WHEN status IN ('SENT', '1') THEN 1
                    WHEN status IN ('FAILED', '2') THEN 2
                    WHEN status IN ('CONFIRMED', '3') THEN 3
                    ELSE 0
                END
            );
        ALTER TABLE agent_command_outbox
            ALTER COLUMN status SET DEFAULT 0;
    END IF;
END $$;

ALTER TABLE agent_command_outbox
    ADD COLUMN IF NOT EXISTS last_sent_at TIMESTAMPTZ;

ALTER TABLE agent_command_outbox
    ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ;

ALTER TABLE agent_command_outbox
    ALTER COLUMN status SET DEFAULT 0;

ALTER TABLE agent_command_outbox
    DROP CONSTRAINT IF EXISTS chk_agent_command_outbox_status;

ALTER TABLE agent_command_outbox
    ADD CONSTRAINT chk_agent_command_outbox_status CHECK (status IN (0, 1, 2, 3));

DROP INDEX IF EXISTS idx_agent_command_outbox_pending_scan;

CREATE INDEX IF NOT EXISTS idx_agent_command_outbox_pending_scan
    ON agent_command_outbox(next_retry_at)
    WHERE status = 0 AND deleted = 0;

CREATE INDEX IF NOT EXISTS idx_agent_command_outbox_sent_scan
    ON agent_command_outbox(last_sent_at)
    WHERE status = 1 AND deleted = 0;

