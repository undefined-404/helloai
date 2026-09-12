-- ============================================================
-- RBAC 基础架构深化（BASE-3.3）：数据权限规则（受控枚举实现）
-- 背景：对齐 JeecgBoot 数据权限能力，但采用**受控枚举**而非动态 SQL 拼接——
--       规则类型白名单（ALL / DEPT / DEPT_AND_CHILD / CUSTOM），后端按枚举解释并
--       转成部门范围 → 用户 id 集合过滤，不拼接任意 SQL（避免注入与性能风险）。
-- 影响：V77~V82 只读，本迁移 V83 递增；rule_flag 默认 0（未配置 = 不过滤，行为不变）。
-- ============================================================

-- ------------------------------------------------------------
-- 1. sys_permission 扩展：rule_flag（是否配置数据权限）
-- ------------------------------------------------------------
ALTER TABLE sys_permission ADD COLUMN IF NOT EXISTS rule_flag SMALLINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN sys_permission.rule_flag IS '是否配置数据权限：0=未配置（默认，不过滤）1=已配置（按 sys_permission_data_rule 过滤）';

-- ------------------------------------------------------------
-- 2. sys_permission_data_rule 数据权限规则表（一个权限码一条规则）
--    rule_type 白名单：ALL 全部 / DEPT 本部门 / DEPT_AND_CHILD 本部门及下级 / CUSTOM 自定义部门
--    rule_value：CUSTOM 时的部门 ID 逗号分隔（如 "10,11,12"）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_permission_data_rule (
    id            BIGINT       NOT NULL PRIMARY KEY,
    permission_id BIGINT       NOT NULL,
    rule_type     VARCHAR(32)  NOT NULL DEFAULT 'ALL',
    rule_value    VARCHAR(512),
    create_by     VARCHAR(64)  NOT NULL DEFAULT '',
    update_by     VARCHAR(64)  NOT NULL DEFAULT '',
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT     NOT NULL DEFAULT 0,
    remark        VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_perm_data_rule_unique ON sys_permission_data_rule(permission_id) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_sys_perm_data_rule_update_time ON sys_permission_data_rule;
CREATE TRIGGER update_sys_perm_data_rule_update_time BEFORE UPDATE ON sys_permission_data_rule
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE sys_permission_data_rule IS '系统权限数据规则表（数据权限，受控枚举）';
COMMENT ON COLUMN sys_permission_data_rule.rule_type IS '规则类型白名单：ALL / DEPT / DEPT_AND_CHILD / CUSTOM';
COMMENT ON COLUMN sys_permission_data_rule.rule_value IS 'CUSTOM 时的部门 ID 逗号分隔';
