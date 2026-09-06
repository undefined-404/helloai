-- ============================================================
-- V68__create_workflow_template.sql
-- 用途：Workflow 模板（N-001，Phase 2 C1-S1）
-- 背景：
--   Workflow = 现有 task/sub_task/调度链的"可复用蓝图 + 一次性实例化生成器"
--   （C1 设计 D1：实例化后直接物化为 task/sub_task，不建第二控制面）。
--   S1 只建模板 + 版本两张表；实例化（S2）复用现有 task/sub_task 表。
-- 关键设计：
--   - workflow_template：模板元信息（status=DRAFT/ACTIVE/ARCHIVED，current_version_id 软引用）
--   - workflow_template_version：不可变版本快照（definition JSONB = 节点集 + 依赖边 + paramsSchema；
--     发布后不可变，历史实例绑定版本快照不受模板改版影响）
--   - definition 校验（节点唯一/角色白名单/依赖引用存在/DAG 无环）在应用层
--     WorkflowDefinitionValidator（纯函数，可单测），SQL 层只做非空约束
-- 参考：doc/design/HelloAI_Phase2_C1_Workflow模板设计预研.md §3.1/§3.2
-- ============================================================

CREATE TABLE IF NOT EXISTS workflow_template (
    id                 BIGINT      NOT NULL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    description        VARCHAR(500),
    status             VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_version_id BIGINT,
    category           VARCHAR(64),
    create_by          VARCHAR(64) NOT NULL DEFAULT '',
    update_by          VARCHAR(64) NOT NULL DEFAULT '',
    create_time        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            SMALLINT    NOT NULL DEFAULT 0,
    remark             VARCHAR(255),
    CONSTRAINT chk_workflow_template_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);
CREATE INDEX IF NOT EXISTS idx_workflow_template_status
    ON workflow_template(status) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_workflow_template_update_time ON workflow_template;
CREATE TRIGGER update_workflow_template_update_time BEFORE UPDATE ON workflow_template
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

CREATE TABLE IF NOT EXISTS workflow_template_version (
    id          BIGINT       NOT NULL PRIMARY KEY,
    template_id BIGINT       NOT NULL,
    version_no  INT          NOT NULL,
    definition  JSONB        NOT NULL DEFAULT '{"nodes":[]}'::jsonb,
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    create_by   VARCHAR(64)  NOT NULL DEFAULT '',
    update_by   VARCHAR(64)  NOT NULL DEFAULT '',
    create_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    remark      VARCHAR(255),
    CONSTRAINT chk_workflow_template_version_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT uk_workflow_template_version_no UNIQUE (template_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_workflow_template_version_template
    ON workflow_template_version(template_id, version_no);
DROP TRIGGER IF EXISTS update_workflow_template_version_update_time ON workflow_template_version;
CREATE TRIGGER update_workflow_template_version_update_time BEFORE UPDATE ON workflow_template_version
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE workflow_template IS 'Workflow 模板（N-001，C1 设计：可复用蓝图，实例化后脱离 Workflow 层）';
COMMENT ON COLUMN workflow_template.status IS '模板状态：DRAFT / ACTIVE / ARCHIVED';
COMMENT ON COLUMN workflow_template.current_version_id IS '当前激活版本（软引用 workflow_template_version.id；发布版本时回填）';
COMMENT ON COLUMN workflow_template.category IS '分类（可选，如 dev-release / data-etl）';
COMMENT ON TABLE workflow_template_version IS 'Workflow 模板版本（不可变快照；发布后不可再编辑）';
COMMENT ON COLUMN workflow_template_version.version_no IS '版本号（模板内递增 1,2,3...）';
COMMENT ON COLUMN workflow_template_version.definition IS '定义 JSONB：{nodes:[{nodeKey,role,spec,constraints,dependsOn}], paramsSchema}；应用层校验节点唯一/角色白名单/依赖引用/DAG 无环';
COMMENT ON COLUMN workflow_template_version.status IS '版本状态：DRAFT / PUBLISHED（发布后不可变）';
