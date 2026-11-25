-- ============================================================
-- 清理脚本：会话附件历史软删残留行（`G-041` 配套，2026-11-25）
-- ------------------------------------------------------------
-- 背景：`ChatAttachmentServiceImpl#delete` 在 `G-041` 之前是「软删行 + 对象真删」。
--   于是每次单条删除都会永久留下一条 `deleted=1` 的行，而它指向的对象早已被
--   `purgeObjectQuietly` 真删掉。这条行造成两处持续的假信号：
--
--   ① 存储对账永久报 dangling：`ChatAttachmentMapper#selectStorageRefAllIncludingDeleted`
--      故意不过滤 `deleted`（对账要保守：只要还有任意一行指向某对象就不算孤儿），
--      于是「对象已删 + 软删行还在」被判成 dangling，且**没有任何后续动作能自愈**。
--   ② 该行对 `selectActiveObjectKeysIn` 又不可见（那边只认 `deleted=0`），
--      所以它也不构成任何有效使用 —— 一条既不使用、也无法自愈的孤儿行。
--
-- 判据：`chat_attachment` 生命周期本身就是「会话删除即回收」（`V121` 建表注释原文），
--   与子任务产物 `attachment` 的「交付审计长期留存」不同 ⇒ 不留墓碑行。
--   已 apply 的修复：`G-041` 起 `delete` / `deleteByConversation` 全部走物理删。
--
-- 幂等性：纯 `DELETE ... WHERE deleted=1`，重复执行第二次删 0 行。
-- 执行方式：**必须先跑第 0 段只读统计**，确认存量规模与抽样内容合理，
--   再执行第 2 段。删除不可回滚，第 1 段是备份建议。
-- ============================================================

-- ────────────────────────────────────────────────
-- 第 0 段：只读统计（不改任何数据，先跑这一段）
-- ────────────────────────────────────────────────

-- 0.1 存量规模：软删行总数 / 涉及会话数 / 关联对象字节数合计
SELECT COUNT(*)                                   AS soft_deleted_rows,
       COUNT(DISTINCT conversation_id)            AS affected_conversations,
       COALESCE(SUM(file_size), 0)                AS total_bytes
FROM chat_attachment
WHERE deleted = 1;

-- 0.2 按年分布（判断是否为长期累积；若集中在某一窗口，说明是某次批量操作留下的）
SELECT date_trunc('month', update_time) AS deleted_month,
       COUNT(*)                         AS rows
FROM chat_attachment
WHERE deleted = 1
GROUP BY 1
ORDER BY 1;

-- 0.3 抽样（人工核对：这些文件确实已无用；`storage_url` 指向的对象应已不存在）
SELECT id, conversation_id, file_name, file_size, storage_url, update_time
FROM chat_attachment
WHERE deleted = 1
ORDER BY update_time DESC
LIMIT 20;

-- 0.4 交叉核对：软删行里是否有**会话仍存活**的（这类要谨慎——
--     行本身已删，但会话还在；按 `G-041` 口径它们同样该清，因为会话侧的
--     「删除这张图」已经完成，行只是墓碑）
SELECT COUNT(*) AS soft_deleted_with_live_conversation
FROM chat_attachment a
WHERE a.deleted = 1
  AND a.conversation_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM requirement_conversation c
              WHERE c.id = a.conversation_id AND c.deleted = 0);

-- ────────────────────────────────────────────────
-- 第 1 段：备份（可选，强烈建议）
-- ────────────────────────────────────────────────
-- 只备份元数据，不备份字节（对象早已 purge，无字节可备）。
-- CREATE TABLE chat_attachment_soft_deleted_bak_20261125 AS
-- SELECT * FROM chat_attachment WHERE deleted = 1;

-- ────────────────────────────────────────────────
-- 第 2 段：清理（确认第 0 段结果后再执行）
-- ────────────────────────────────────────────────
-- 注意：对象回收**不在本脚本内做**。这些行的对象在当初软删时已被
--   `purgeObjectQuietly` 尝试删除（失败则由对账兜底）。本脚本只清行。
--   若要在清理后确认对象也已不存在，跑对账巡检即可。

DELETE FROM chat_attachment WHERE deleted = 1;

-- ────────────────────────────────────────────────
-- 第 3 段：验证（期望 soft_deleted_rows = 0）
-- ────────────────────────────────────────────────
SELECT COUNT(*) AS remaining_soft_deleted FROM chat_attachment WHERE deleted = 1;