-- ============================================================
-- V133: 存量任务归属回填（PA-5 执行产物可见性）
--
-- 背景（2026-11-02）：PA-1 在 V132 只给 task 加了可空 owner_id 列，存量任务 owner_id
-- 仍为 NULL。PA-5「我的产物」的执行产物源按 D1 口径直滤 owner（owner == 当前用户，
-- 不含 NULL），故 PA-1 之前的历史任务及其子任务产物在「我的产物」里不可见。
--
-- 处理口径（单用户先行，见 PA 计划 §0 ①「不做多租户」）：
--   - 把 owner_id 仍为 NULL 的未删除任务，一次性回填为内置管理员（username = 'admin'）。
--   - admin id 不写死：按 username 从 sys_user 实时查出，兼容不同环境 / 重建库后的
--     id 漂移；admin 不存在时语句整体不生效（EXISTS 守卫）。
--   - 只动 owner_id IS NULL 的行：已归属的行不受影响；可重复执行（幂等）。
--
-- 边界：
--   - 这是「单用户 / 开发环境」的历史归属归并；真多租户场景不应这样回填，届时需按真实
--     用户重新归属。
--   - 会话 / 消息的 NULL 归属不回填：查询侧已按「owner == 当前用户 OR owner IS NULL」
--     宽松并入，无需改数据。
-- 回滚：原值为 NULL，回填不可无损还原；如需还原可 SET owner_id = NULL（会丢失回填结果）。
-- ============================================================

UPDATE task
SET owner_id = (
        SELECT u.id
        FROM sys_user u
        WHERE u.username = 'admin'
          AND u.deleted = 0
        LIMIT 1
    )
WHERE owner_id IS NULL
  AND deleted = 0
  AND EXISTS (
        SELECT 1
        FROM sys_user u
        WHERE u.username = 'admin'
          AND u.deleted = 0
    );
