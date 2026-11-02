-- ============================================================
-- V132: 个人助理工作台地基（PA-1 / PA-2 / PA-3）
--
-- 背景（2026-11-02）：个人助理工作台专项（PA-x，见
-- plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md）。
-- 本迁移承载三条地基迁移：
--   PA-1 owner 落会话/任务/长期记忆：先加可空 owner_id 列；存量回填与查询过滤
--        在应用层做（见 LongTermMemoryArchive 等），本迁移不写 SQL 回填。
--   PA-3 assistant_profile 表（身份卡数据源，字段抄 LobsterAI coworkStore.ts）。
--   PA-2 画像表 user_profile、user_preference（USER 型长期记忆 + 偏好键值）。
--
-- 设计口径（PA-1，宽松并入）：
--   - 同步请求线程写真实 owner（登录用户 id）；流式异步线程取不到时安全降级 NULL。
--   - 查询过滤为「owner == 当前用户 OR owner IS NULL」，避免老数据（V132 前无此列）
--     在「我的会话/任务/记忆」里瞬间消失。
--   - 不建第二套权限体系；owner 直滤与现有 RBAC（Sa-Token 角色）叠加于查询面。
--
-- 红线：V1~V131 只读；仅加可空列 + 新建表 + 索引；不改既有数据。
-- 回滚：本迁移新建对象可整体 DROP，加列可 DROP COLUMN（三段独立）。
-- ============================================================

-- ── PA-1：owner 落点（可空列，无默认；历史数据 NULL）────────────────────
ALTER TABLE requirement_conversation ADD COLUMN IF NOT EXISTS owner_id BIGINT;
ALTER TABLE requirement_message     ADD COLUMN IF NOT EXISTS owner_id BIGINT;
ALTER TABLE task                    ADD COLUMN IF NOT EXISTS owner_id BIGINT;
ALTER TABLE long_term_memory        ADD COLUMN IF NOT EXISTS owner_id BIGINT;

COMMENT ON COLUMN requirement_conversation.owner_id IS '归属用户 id（PA-1）：同步请求线程写真实登录用户；流式异步线程取不到时 NULL；查询按 owner==当前用户 OR IS NULL 过滤';
COMMENT ON COLUMN requirement_message.owner_id     IS '归属用户 id（PA-1）：随会话归属，查询过滤同会话口径';
COMMENT ON COLUMN task.owner_id                     IS '归属用户 id（PA-1）：经会话终稿建任务时继承会话 owner；手动建任务写当前登录用户';
COMMENT ON COLUMN long_term_memory.owner_id         IS '归属用户 id（PA-1）：USER 型记忆必填；SESSION 随会话 owner';

-- 按用户查会话 / 任务 / 长期记忆的索引（业界证据：Dify conversation_app_from_user_idx）
CREATE INDEX IF NOT EXISTS idx_requirement_conversation_owner
    ON requirement_conversation (owner_id, deleted);
CREATE INDEX IF NOT EXISTS idx_task_owner
    ON task (owner_id, deleted);
CREATE INDEX IF NOT EXISTS idx_long_term_memory_owner
    ON long_term_memory (owner_id, deleted);
CREATE INDEX IF NOT EXISTS idx_requirement_message_owner
    ON requirement_message (owner_id, deleted);

-- ── PA-3：助理身份表（身份卡数据源）──────────────────────────────────────
CREATE TABLE IF NOT EXISTS assistant_profile (
    id           BIGINT       NOT NULL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    identity     VARCHAR(255) NOT NULL DEFAULT '',
    model        VARCHAR(128) NOT NULL DEFAULT '',
    skill_ids    JSONB        NOT NULL DEFAULT '[]',
    pinned       SMALLINT     NOT NULL DEFAULT 0,
    greeting     VARCHAR(255) NOT NULL DEFAULT '',
    owner_id     BIGINT,
    create_by    VARCHAR(64)  NOT NULL DEFAULT '',
    update_by    VARCHAR(64)  NOT NULL DEFAULT '',
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    remark       VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_assistant_profile_owner
    ON assistant_profile (owner_id, deleted);
COMMENT ON TABLE assistant_profile IS '助理身份（PA-3）：对话页头部身份卡数据源；单助理展示，非人格市场';
COMMENT ON COLUMN assistant_profile.name IS '助理名';
COMMENT ON COLUMN assistant_profile.identity IS '身份定位（一句）';
COMMENT ON COLUMN assistant_profile.model IS '默认模型（展示）';
COMMENT ON COLUMN assistant_profile.skill_ids IS '技能 id 列表（JSONB）';
COMMENT ON COLUMN assistant_profile.pinned IS '是否置顶展示';

-- ── PA-2：画像表 / 偏好表（USER 型长期记忆 + 结构化画像职责分层）────────────
CREATE TABLE IF NOT EXISTS user_profile (
    id              BIGINT       NOT NULL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    profile_json    JSONB        NOT NULL DEFAULT '{}',
    source          VARCHAR(32)  NOT NULL DEFAULT 'RULE',
    version         INTEGER      NOT NULL DEFAULT 1,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_profile_user
    ON user_profile (user_id) WHERE deleted = 0;
COMMENT ON TABLE user_profile IS '用户结构化画像（PA-2）：字段级抽取 + 规则合并 + 时间戳覆盖；双轨分层之一（long_term_memory=精选子集，requirement_message=真相源）';

CREATE TABLE IF NOT EXISTS user_preference (
    id           BIGINT       NOT NULL PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    pref_key     VARCHAR(128) NOT NULL,
    pref_value   TEXT         NOT NULL,
    create_by    VARCHAR(64)  NOT NULL DEFAULT '',
    update_by    VARCHAR(64)  NOT NULL DEFAULT '',
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    remark       VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_preference_key
    ON user_preference (user_id, pref_key) WHERE deleted = 0;
COMMENT ON TABLE user_preference IS '用户偏好键值（PA-2）：常用偏好/快捷项，与 user_profile 配套';