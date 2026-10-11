-- ============================================================
-- V107: REF-4.2/4.3 知识库（RAG）存储表 + 查看/管理权限码
--
-- 背景（2026-10-11）：REF-4.0a（V106）已启用 pgvector 的 vector 扩展；
-- 本迁移建 RAG 存储载体，供知识库摄入（ingest）/ 检索（search）使用。
--   - kb_chunk：知识库分片（正文 + vector(1024) 嵌入 + 来源）
--   - 索引：HNSW（cosine）—— 维度 1024 已冻结（ADR-002，单向门）
--
-- 权限码（接续 V104[skill:install=125/skill:uninstall=126]、V105[backup:view=127/backup:run=128]）：
--   kb:view=129    会话/检索侧读取知识库        → 绑 ADMIN(2) + NORMAL_USER(3)
--   kb:manage=130  管理端摄入 / 清空知识库      → 绑 ADMIN(2)
--
-- 不建菜单行：检索工具（search_knowledge）经 ToolCallbackContributor 变更，
--   管理端为弹窗/验证数据源，非独立页面（与 V103 同一红线）。
-- 红线：V1~V106 只读，本迁移递增为 V107；仅新增表 + 权限码与角色关联，不新增权限体系。
-- 回滚：kb_chunk 表 DROP；权限码按 code 反向删 sys_role_permission / sys_permission（无 DDL）。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 知识库分片表
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kb_chunk (
    id         BIGINT PRIMARY KEY,
    source     VARCHAR(255) NOT NULL,
    chunk_idx  INT          NOT NULL,
    content    TEXT         NOT NULL,
    embedding  vector(1024) NOT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by  VARCHAR(64)  NOT NULL DEFAULT '',
    update_by  VARCHAR(64)  NOT NULL DEFAULT '',
    deleted    SMALLINT     NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_hnsw
    ON kb_chunk USING hnsw (embedding vector_cosine_ops);

-- ------------------------------------------------------------
-- 2) 权限码事实源（id 129 = kb:view，130 = kb:manage）
-- ------------------------------------------------------------
INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES
    (129, 'kb:view',   '知识库-查看检索', 'API', 119, 'system', 'system'),
    (130, 'kb:manage', '知识库-摄入管理', 'API', 120, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- ------------------------------------------------------------
-- 3) 角色绑定：kb:view → ADMIN(2) + NORMAL_USER(3)
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0 AND p.code = 'kb:view'
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000500000 + p.id, 3, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0 AND p.code = 'kb:view'
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------
-- 4) 角色绑定：kb:manage → ADMIN(2)（写入/管理仅 ADMIN）
-- ------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0 AND p.code = 'kb:manage'
ON CONFLICT DO NOTHING;