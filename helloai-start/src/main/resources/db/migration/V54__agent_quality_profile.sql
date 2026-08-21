-- ============================================================
-- V54: 反馈回路第 1 层 —— Agent 质量画像表 agent_quality_profile
-- ------------------------------------------------------------
-- 背景：差距表 N20（反馈回路第 1 层质量画像）。评审建议「质量画像回灌」的落库位：
--   - 画像随 review_record 落库同事务增量维护（QualityProfileUpdater 收口在
--     ReviewServiceImpl.recordAutoReview / createReview 两处），规避批量重算；
--   - AgentSelector resolveComparator 在 dutyRank 之后插入 qualityRank（画像分
--     排序回灌调度选人）；AgentDutyLeaseServiceImpl.resolveTtlMinutes 将
--     performanceScore 升级为复合分（失败折算分 + 质量分加权）；
--   - 对账兜底：AgentQualityProfileService.rebuild(agentId) 从 review_record
--     全量重算，供 admin 端点与 verify-quality-profile.ps1 对账。
--
-- 设计要点：
--   1) 执行者维度取 sub_task.assigned_agent_id（落库时刻归属，改派场景口径自洽）；
--   2) 统计项：轮次计数（reviewed_count）、通过数（approved_count）、首轮口径
--      （first_reviewed_count / first_pass_count）、评分累加（total_score）、
--      返工轮次（rework_round_sum）、issues 四元组 [defect] 标签计数
--      （issue_defect_stats，正则解析失败降级跳过）；
--   3) reviewer_reviewed_count / reviewer_disagreement_count 为 Phase 4
--      （Reviewer 双审 + 抽检）预留，Phase 1 只建列不写入；
--   4) last_review_record_id 记录最近一次纳入统计的 review_record.id，
--      供增量更新幂等判定（同一记录重复回调不重复计数）与对账起点追溯；
--   5) agent_id 部分唯一索引（WHERE deleted=0）：软删后同 agent 可重建画像，
--      与 llm_provider_model（V50）同款逻辑删除对齐模式。
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_quality_profile (
    id                          BIGINT      NOT NULL PRIMARY KEY,
    agent_id                    BIGINT      NOT NULL,
    reviewed_count              INT         NOT NULL DEFAULT 0,
    approved_count              INT         NOT NULL DEFAULT 0,
    first_reviewed_count        INT         NOT NULL DEFAULT 0,
    first_pass_count            INT         NOT NULL DEFAULT 0,
    total_score                 INT         NOT NULL DEFAULT 0,
    rework_round_sum            INT         NOT NULL DEFAULT 0,
    issue_defect_stats          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    reviewer_reviewed_count     INT         NOT NULL DEFAULT 0,
    reviewer_disagreement_count INT         NOT NULL DEFAULT 0,
    last_review_record_id       BIGINT,
    deleted                     SMALLINT    NOT NULL DEFAULT 0,
    create_by                   VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by                   VARCHAR(64) NOT NULL DEFAULT 'system',
    create_time                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark                      VARCHAR(255)
);

-- agent_id 部分唯一索引：仅活跃记录 (deleted=0) 保证每 Agent 至多一条画像
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_quality_profile_agent
    ON agent_quality_profile (agent_id)
    WHERE deleted = 0;

-- 常用读取路径：按 agent_id 精确定位画像（调度回灌 / 历史表现摘要渲染）
CREATE INDEX IF NOT EXISTS idx_agent_quality_profile_agent_deleted
    ON agent_quality_profile (agent_id, deleted)
    WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_agent_quality_profile_update_time ON agent_quality_profile;
CREATE TRIGGER update_agent_quality_profile_update_time
    BEFORE UPDATE ON agent_quality_profile
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE agent_quality_profile IS 'Agent 质量画像表（反馈回路第 1 层）：随 review_record 落库同事务增量维护，调度回灌 + 动态 TTL 复合分 + 历史表现摘要的数据源';
COMMENT ON COLUMN agent_quality_profile.id IS '主键ID（Snowflake）';
COMMENT ON COLUMN agent_quality_profile.agent_id IS '被评审的执行 Agent ID（取 sub_task.assigned_agent_id 落库时刻归属）';
COMMENT ON COLUMN agent_quality_profile.reviewed_count IS '累计被评审次数';
COMMENT ON COLUMN agent_quality_profile.approved_count IS '累计通过（APPROVED）次数';
COMMENT ON COLUMN agent_quality_profile.first_reviewed_count IS '首轮评审（round=1）累计次数';
COMMENT ON COLUMN agent_quality_profile.first_pass_count IS '首轮即通过（round=1 且 APPROVED）累计次数';
COMMENT ON COLUMN agent_quality_profile.total_score IS '评审评分累加（score 总和）';
COMMENT ON COLUMN agent_quality_profile.rework_round_sum IS '返工轮次累计（round>1 的轮次贡献值）';
COMMENT ON COLUMN agent_quality_profile.issue_defect_stats IS 'issues 四元组 [defect] 标签计数（JSONB map: 标签名 -> 出现次数）';
COMMENT ON COLUMN agent_quality_profile.reviewer_reviewed_count IS '作为 Reviewer 累计核验次数（Phase 4 双审/抽检预留）';
COMMENT ON COLUMN agent_quality_profile.reviewer_disagreement_count IS '作为 Reviewer 产生分歧次数（Phase 4 双审预留）';
COMMENT ON COLUMN agent_quality_profile.last_review_record_id IS '最近一次纳入统计的 review_record.id（增量更新幂等判定 + 对账起点）';
COMMENT ON COLUMN agent_quality_profile.deleted IS '逻辑删除标记：0-未删除，1-已删除';
COMMENT ON COLUMN agent_quality_profile.create_by IS '创建人';
COMMENT ON COLUMN agent_quality_profile.update_by IS '更新人';
COMMENT ON COLUMN agent_quality_profile.create_time IS '创建时间';
COMMENT ON COLUMN agent_quality_profile.update_time IS '更新时间';
COMMENT ON COLUMN agent_quality_profile.remark IS '备注';

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
    WHERE schemaname = 'public' AND tablename = 'agent_quality_profile';
    SELECT COUNT(*) INTO idx_count
    FROM pg_indexes
    WHERE tablename = 'agent_quality_profile' AND indexname = 'uk_agent_quality_profile_agent';
    SELECT COUNT(*) INTO col_count
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name = 'agent_quality_profile'
      AND column_name IN ('agent_id', 'reviewed_count', 'approved_count',
                          'first_reviewed_count', 'first_pass_count', 'total_score',
                          'rework_round_sum', 'issue_defect_stats',
                          'reviewer_reviewed_count', 'reviewer_disagreement_count',
                          'last_review_record_id');
    RAISE NOTICE '[V54] 画像表已就绪: tbl=% , 唯一索引=% , 核心列=%/11', tbl_count, idx_count, col_count;
END $$;
