-- ============================================================
-- V57: Reviewer 抽检日志表 review_recheck_log
-- ------------------------------------------------------------
-- 背景：差距表 N20（反馈回路）Phase 4 抽检机制——对已 APPROVED 的
-- review_record 按比例抽样复审，度量 Reviewer「放水率」（原判 APPROVED
-- 但复审 REJECTED 记为放水），并驱动画像表 reviewer 维度计数增量。
--
-- 设计要点：
--   1) 实体/表归属 task 域（与 review_record 同域先例，V54 注释明确
--      「评审相关实体归 task 域」）；Phase 5 若统一迁移 review 域时随
--      ReviewRecord 一并评估；
--   2) 一行 = 一次抽检复审：original_result 记录被抽检 record 的原判，
--      recheck_result 记录复审判定，discrepancy=1 表示放水
--      （原 APPROVED 且复审 REJECTED）；
--   3) review_record_id 无唯一约束：同一 record 可被多轮抽检命中
--      （时间窗口推进后再次入选），抽检候选查询按 NOT EXISTS 排除
--      已抽检记录（单窗口内每 record 至多抽一次）；
--   4) 抽检只度量不改状态：子任务已按原判 DONE/REWORK 推进，抽检结果
--      仅落日志 + timeline 观测，放水时供人工复核参考。
-- ============================================================

CREATE TABLE IF NOT EXISTS review_recheck_log (
    id               BIGINT      NOT NULL PRIMARY KEY,
    review_record_id BIGINT      NOT NULL REFERENCES review_record(id),
    sub_task_id      BIGINT      NOT NULL REFERENCES sub_task(id),
    original_result  VARCHAR(32) NOT NULL,
    recheck_result   VARCHAR(32) NOT NULL,
    discrepancy      SMALLINT    NOT NULL DEFAULT 0,
    reviewer_agent   BIGINT      NOT NULL,
    score            INT         NOT NULL DEFAULT 0,
    issues           TEXT,
    comment          TEXT,
    create_by        VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by        VARCHAR(64) NOT NULL DEFAULT 'system',
    create_time      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          SMALLINT    NOT NULL DEFAULT 0,
    remark           VARCHAR(255),
    CONSTRAINT chk_recheck_result CHECK (recheck_result IN ('APPROVED', 'REJECTED'))
);

-- 常用读取路径：按原审查记录查抽检历史（同一 record 多轮抽检追溯）
CREATE INDEX IF NOT EXISTS idx_review_recheck_record
    ON review_recheck_log (review_record_id, deleted)
    WHERE deleted = 0;

-- 常用读取路径：按子任务查抽检历史（前端时序图/对账）
CREATE INDEX IF NOT EXISTS idx_review_recheck_sub_task
    ON review_recheck_log (sub_task_id, deleted)
    WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_review_recheck_log_update_time ON review_recheck_log;
CREATE TRIGGER update_review_recheck_log_update_time
    BEFORE UPDATE ON review_recheck_log
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE review_recheck_log IS 'Reviewer 抽检日志表（反馈回路 Phase 4）：对已 APPROVED 审查记录抽样复审，度量放水率并驱动 reviewer 维度画像计数';
COMMENT ON COLUMN review_recheck_log.id IS '主键ID（Snowflake）';
COMMENT ON COLUMN review_recheck_log.review_record_id IS '被抽检的审查记录 ID（review_record.id）';
COMMENT ON COLUMN review_recheck_log.sub_task_id IS '被抽检子任务 ID';
COMMENT ON COLUMN review_recheck_log.original_result IS '原判结果：APPROVED/REJECTED';
COMMENT ON COLUMN review_recheck_log.recheck_result IS '复审判定结果：APPROVED/REJECTED';
COMMENT ON COLUMN review_recheck_log.discrepancy IS '放水标记：1=原 APPROVED 复审 REJECTED（放水），0=一致';
COMMENT ON COLUMN review_recheck_log.reviewer_agent IS '执行复审的 Reviewer Agent ID';
COMMENT ON COLUMN review_recheck_log.score IS '复审评分（1-5，判定不可用时兜底）';
COMMENT ON COLUMN review_recheck_log.issues IS '复审驳回时的问题描述';
COMMENT ON COLUMN review_recheck_log.comment IS '复审意见';
COMMENT ON COLUMN review_recheck_log.create_by IS '创建人';
COMMENT ON COLUMN review_recheck_log.update_by IS '更新人';
COMMENT ON COLUMN review_recheck_log.create_time IS '创建时间';
COMMENT ON COLUMN review_recheck_log.update_time IS '更新时间';
COMMENT ON COLUMN review_recheck_log.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN review_recheck_log.remark IS '备注';

-- ============================================================
-- 验证
-- ============================================================

DO $$
DECLARE
    tbl_count INT;
    idx_count INT;
    col_count INT;
BEGIN
    SELECT COUNT(*) INTO tbl_count
    FROM pg_tables
    WHERE schemaname = 'public' AND tablename = 'review_recheck_log';
    SELECT COUNT(*) INTO idx_count
    FROM pg_indexes
    WHERE tablename = 'review_recheck_log' AND indexname = 'idx_review_recheck_record';
    SELECT COUNT(*) INTO col_count
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'review_recheck_log'
      AND column_name IN ('review_record_id', 'sub_task_id', 'original_result',
                          'recheck_result', 'discrepancy', 'reviewer_agent');
    RAISE NOTICE '[V57] 抽检日志表已就绪: tbl=% , 索引=% , 核心列=%/6', tbl_count, idx_count, col_count;
END $$;
