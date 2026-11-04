-- ============================================================
-- V139: 审查结论三态（RV-2）——reviews 两张表约束放宽到 INCONCLUSIVE
-- ------------------------------------------------------------
-- 背景：实施计划 `doc/plan/HelloAI 核验双域改造实施计划（2026-11-04）.md`
-- `RV-2`。判定自相矛盾（pass=false 且 score>=5）不再当「驳回」处理，
-- 而是落 INCONCLUSIVE：不计入通过率 / 平均分分母，不触发返工，
-- 子任务停留 REVIEW 等人工。
--
-- 同批两处约束（草稿曾漏第二处）：
--   1) review_record.chk_review_result
--   2) review_recheck_log.chk_recheck_result
--
-- 只放宽取值域，不改列类型 / 默认值 / 索引。
-- ============================================================

ALTER TABLE review_record DROP CONSTRAINT IF EXISTS chk_review_result;
ALTER TABLE review_record ADD CONSTRAINT chk_review_result
    CHECK (result IN ('APPROVED', 'REJECTED', 'INCONCLUSIVE'));

ALTER TABLE review_recheck_log DROP CONSTRAINT IF EXISTS chk_recheck_result;
ALTER TABLE review_recheck_log ADD CONSTRAINT chk_recheck_result
    CHECK (recheck_result IN ('APPROVED', 'REJECTED', 'INCONCLUSIVE'));

COMMENT ON COLUMN review_record.result IS
    '审查结果：APPROVED/REJECTED/INCONCLUSIVE（INCONCLUSIVE = 判定不可采信，不计入统计分母、不触发返工）';
