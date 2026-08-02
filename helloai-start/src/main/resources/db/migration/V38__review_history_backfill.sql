-- ============================================================
-- V38__review_history_backfill.sql
-- 用途：reviewHistory 多轮累积——把历史 lastAutoReview 单 Map 回填为 reviewHistory 数组
-- 背景：
--   本轮（§6.41）把 sub_task.context.lastAutoReview (Map) 重构为 reviewHistory (List<Map>)，
--   每次驳回 append 一条，prompt 拼接时按轮次铺开 reviewer 意见。
--   历史上已有驳回数据的子任务 context 里只有 lastAutoReview，没有 reviewHistory。
--   本次回填保证：
--     - reviewHistory 为空但 lastAutoReview 存在的子任务 → 包成单元素数组
--     - 两字段都存在的子任务 → 不动（幂等）
--   不需要新建表/列（reviewHistory 仍存 sub_task.context JSONB）
-- 兼容：
--   - appendReworkContext 同时识别新 reviewHistory 与旧 lastAutoReview，
--     V38 落地前后 prompt 拼接都不中断
--   - lastAutoReview 字段保留不删（V36 回填前的中间过渡仍能命中）
-- ============================================================

UPDATE sub_task
SET context = context || jsonb_build_object(
    'reviewHistory',
    CASE
        WHEN context->'reviewHistory' IS NOT NULL THEN context->'reviewHistory'
        WHEN context->'lastAutoReview' IS NOT NULL THEN
            jsonb_build_array(
                jsonb_build_object(
                    'round', 1,
                    'ts', COALESCE(update_time::text, create_time::text, ''),
                    'reviewerAgentId', context->'lastAutoReview'->'reviewerAgentId',
                    'issues', context->'lastAutoReview'->'issues',
                    'comment', context->'lastAutoReview'->'comment',
                    'score', context->'lastAutoReview'->'score',
                    'executorDoneIssues', '[]'::jsonb
                )
            )
        ELSE '[]'::jsonb
    END
)
WHERE deleted = 0
  AND context->'reviewHistory' IS NULL
  AND context->'lastAutoReview' IS NOT NULL;

-- 验证日志
DO $$
BEGIN
    RAISE NOTICE '[V38] reviewHistory 历史回填完成，影响行数 %',
        (SELECT COUNT(*) FROM sub_task WHERE context->'reviewHistory' IS NOT NULL);
END $$;