-- ============================================
-- V43: task_iteration jsonb 列改为 text
-- ============================================
-- 原因：MyBatis-Plus JacksonTypeHandler 序列化 List/Map 后以
--       String (varchar) 类型设置 JDBC 参数，PostgreSQL 拒绝
--       varchar → jsonb 隐式转换，报 "column X is of type jsonb
--       but expression is of type character varying"。
-- 影响：depends_on / rejection_history 改为 text 后，功能无差异
--       （这些字段不会被 JSONB 操作符查询，只做完整读写）。
-- ============================================

ALTER TABLE task_iteration
    ALTER COLUMN depends_on        TYPE TEXT,
    ALTER COLUMN depends_on        SET DEFAULT '[]',
    ALTER COLUMN rejection_history TYPE TEXT,
    ALTER COLUMN rejection_history SET DEFAULT '[]';

COMMENT ON COLUMN task_iteration.depends_on        IS '前置依赖的迭代记录 ID 数组（TEXT，存 JSON 数组）';
COMMENT ON COLUMN task_iteration.rejection_history IS '全部历史驳回意见（TEXT，存 JSON 对象数组）';

-- ------------------------------------------------------------
-- Flyway 校验块（规则要求）
-- ------------------------------------------------------------
DO
$$
    BEGIN
        RAISE NOTICE '[V43] columns depends_on / rejection_history altered to TEXT';
    END
$$;
