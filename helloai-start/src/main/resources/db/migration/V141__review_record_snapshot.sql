-- ============================================================
-- V141: 审查记录增判定快照列（RV-5）
-- ------------------------------------------------------------
-- 背景：实施计划 `doc/plan/HelloAI 核验双域改造实施计划（2026-11-04）.md`
-- `RV-5`。抽检复判读的是子任务「当前视图」，对 REJECTED 记录，返工后产出
-- 已被覆盖 ⇒ 复判测到的不是原判定对象。本列把「判定当时看到了什么」固定为
-- 摘要：可读附件（id/fileName/fileSize）+ 执行产出（chars/sha256），不存全文。
--
-- 只增列，不改类型 / 约束 / 索引；存量行为 NULL ⇒ 历史行按当前视图处理
-- （不复判拦截，避免把老数据整体排除出抽检）。
-- ============================================================

ALTER TABLE review_record ADD COLUMN IF NOT EXISTS snapshot JSONB;

COMMENT ON COLUMN review_record.snapshot IS
    '判定快照（RV-5）：{schemaVersion,attachmentCount,attachmentsTruncated,attachments:[{id,fileName,fileSize}],output:{chars,sha256}}；NULL = 历史行（无快照，复判按当前视图）';