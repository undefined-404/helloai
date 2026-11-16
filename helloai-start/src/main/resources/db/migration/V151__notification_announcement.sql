-- ══════════════════════════════════════════════════════════════════════════
-- V151：人侧站内消息 + 置顶公告（G-035 / MSG-1~6，一个小版本合并迁移）
--
-- 本批「站内消息与置顶公告」作为一个小版本交付，迁移合并为单文件（用户裁定 2026-11-15）：
--   §1 user_notification —— owner-scoped 布尔已读消息（无状态机，有效性判深链回源）;
--   §2 announcement / announcement_revision / announcement_seen —— 单条置顶 + 按版本已读;
--   §3 RBAC —— notice:view / notice:edit 动作码 + 公告管理菜单 + ADMIN 授予 + 落地断言。
-- 设计：doc/design/HelloAI 站内消息与置顶公告设计.md §3.7/§3.8/§3.9/§3.10。
--
-- 开关：notification.enabled / announcement.enabled 默认 OFF（键缺省即 OFF，无需种子）;
-- OFF 停发 + 隐藏（publish 早退、读取返回空），公告写入不受限。回滚见各段注记。
-- ══════════════════════════════════════════════════════════════════════════

-- ──────────────────────────────────────────────────────────────────────────
-- §1 人侧站内消息 user_notification（MSG-2）
--
-- 幂等最终防线（跨副本）：uk_user_notification_dedupe。
-- COALESCE(receiver_id, 0) 不可省——Postgres 唯一索引把 NULL 视为互不相等，
-- 广播行（receiver_id IS NULL）走普通唯一索引根本不去重（设计 §3.3）。
-- ──────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS user_notification (
    id           BIGINT       NOT NULL PRIMARY KEY,
    receiver_id  BIGINT,
    type         VARCHAR(64)  NOT NULL,
    severity     VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    title        VARCHAR(255) NOT NULL,
    summary      TEXT,
    dedupe_key   VARCHAR(160) NOT NULL,
    ref_type     VARCHAR(32),
    ref_id       BIGINT,
    payload      JSONB,
    is_read      SMALLINT     NOT NULL DEFAULT 0,
    read_at      TIMESTAMPTZ,
    is_archived  SMALLINT     NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ,
    create_by    VARCHAR(64)  NOT NULL DEFAULT '',
    update_by    VARCHAR(64)  NOT NULL DEFAULT '',
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark       VARCHAR(255),
    deleted      SMALLINT     NOT NULL DEFAULT 0
);

COMMENT ON TABLE user_notification IS '人侧站内消息（G-035，owner-scoped 布尔已读，无状态机）';
COMMENT ON COLUMN user_notification.receiver_id IS '归属人 sys_user.id；NULL = 广播';
COMMENT ON COLUMN user_notification.dedupe_key IS '幂等键：{type}:{事件码}:{实例} 或 {type}:{sourceType}:{sourceId}:{instanceKey}';
COMMENT ON COLUMN user_notification.payload IS '渲染数据（JSONB），仅作列表摘要，不作事实依据';

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_notification_dedupe
    ON user_notification(dedupe_key, COALESCE(receiver_id, 0)) WHERE deleted = 0;

CREATE INDEX IF NOT EXISTS idx_user_notification_unread
    ON user_notification(receiver_id, is_read, severity, create_time)
    WHERE is_read = 0 AND is_archived = 0 AND deleted = 0;

-- ──────────────────────────────────────────────────────────────────────────
-- §2 置顶公告三表 announcement / announcement_revision / announcement_seen（MSG-6）
--
-- 公告与事件消息二分（设计 §3.1）：消息是 owner-scoped 布尔已读，公告是「全员按版本判已读」。
-- 已读 = seen_version >= version（不是布尔位）——管理员更新内容即 version+1，全员重新未读，
-- 正是「打开项目第一时间看到最近更新了什么」（设计 §3.7）。
--
-- 单条置顶语义（第一期不做多条）：announcement 表承载唯一一条置顶内容的「工作副本」
-- （title/body 为管理员可编辑态）;每次「发布」把当前工作副本快照进 announcement_revision
-- （不可改写的编辑历史），version 计发布次数（首发布为 1）。顶部公告条渲染的是「最新已发布
-- 快照」（revision），而非工作副本——故管理员编辑草稿不会改变线上展示，须再次发布才生效。
-- ──────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS announcement (
    id            BIGINT       NOT NULL PRIMARY KEY,
    title         VARCHAR(255) NOT NULL,
    body          TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    pinned        SMALLINT     NOT NULL DEFAULT 1,
    version       INT          NOT NULL DEFAULT 1,
    published_at  TIMESTAMPTZ,
    create_by     VARCHAR(64)  NOT NULL DEFAULT '',
    update_by     VARCHAR(64)  NOT NULL DEFAULT '',
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark        VARCHAR(255),
    deleted       SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT chk_announcement_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

COMMENT ON TABLE announcement IS '置顶公告工作副本（G-035，单条置顶，version 计发布次数）';
COMMENT ON COLUMN announcement.title IS '标题（工作副本，管理员可编辑;线上展示取最新 revision 快照）';
COMMENT ON COLUMN announcement.body IS '正文（工作副本，管理员可编辑;线上展示取最新 revision 快照）';
COMMENT ON COLUMN announcement.status IS 'DRAFT-草稿 / PUBLISHED-已发布 / ARCHIVED-已归档';
COMMENT ON COLUMN announcement.version IS '最近一次发布的版本号（首发布为 1，每次发布自增）';

CREATE TABLE IF NOT EXISTS announcement_revision (
    id              BIGINT       NOT NULL PRIMARY KEY,
    announcement_id BIGINT       NOT NULL REFERENCES announcement (id),
    version         INT          NOT NULL,
    title           VARCHAR(255) NOT NULL,
    body            TEXT         NOT NULL,
    published_by    VARCHAR(64)  NOT NULL DEFAULT '',
    published_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark          VARCHAR(255),
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uq_announcement_revision UNIQUE (announcement_id, version)
);

COMMENT ON TABLE announcement_revision IS '公告发布快照（编辑历史，不可改写;每次发布留一份全量）';
COMMENT ON COLUMN announcement_revision.published_by IS '发布人 sys_user.id（字符串留痕）';

CREATE TABLE IF NOT EXISTS announcement_seen (
    id              BIGINT      NOT NULL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES sys_user (id),
    announcement_id BIGINT      NOT NULL REFERENCES announcement (id),
    seen_version    INT         NOT NULL,
    seen_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       VARCHAR(64) NOT NULL DEFAULT '',
    update_by       VARCHAR(64) NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark          VARCHAR(255),
    deleted         SMALLINT    NOT NULL DEFAULT 0,
    CONSTRAINT uq_announcement_seen UNIQUE (user_id, announcement_id)
);

COMMENT ON TABLE announcement_seen IS '公告按版本已读位（seen_version 单调递增;已读 = seen_version >= version）';

-- ──────────────────────────────────────────────────────────────────────────
-- §3 置顶公告 RBAC（MSG-6）
--
-- 两段式动作码（对齐 AdminConfigController 的 config:view / config:edit）：
--   notice:view —— 公告管理菜单可见性 + 读取动作（草稿/历史/迁移辅助视图）;type=MENU，
--                  挂「系统设置」(settings:view, id=17) 下，component=settings/AnnouncementManage。
--                  MENU 码同样进 Sa-Token 权限集（listPermissionCode 不区分 type），故兼作读取鉴权。
--   notice:edit —— 公告写入动作（保存草稿/发布/归档）;type=API。
--
-- 授予 ADMIN(2);SUPER_ADMIN 经 "*" 通配全量可见，无需角色绑定。NORMAL_USER/GUEST 不授予
-- ——公告管理是平台管理职能;普通用户只读「已发布顶部条」（/announcement/published 登录即可读，
-- 无动作码，设计 §3.9）。
--
-- id 206/207 接续 V148 的 204/205（菜单分组占 200~203）;若被占用 ON CONFLICT 静默跳过，
-- 故末尾按 code 断言（与 V148 同形，避免「埋雷 + 排雷」两个迁移）。
-- 回滚：DELETE FROM sys_role_permission WHERE permission_id IN (206,207);
--       DELETE FROM sys_permission WHERE id IN (206,207);
-- ──────────────────────────────────────────────────────────────────────────

-- 3.1 权限点：管理菜单（MENU）+ 写入动作（API）
INSERT INTO sys_permission (id, code, name, type, parent_id, path, icon, component, sort, create_by, update_by)
VALUES (206, 'notice:view', '公告管理', 'MENU', 17, '/system/announcement', 'Notification', 'settings/AnnouncementManage', 70, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_permission (id, code, name, type, sort, create_by, update_by)
VALUES (207, 'notice:edit', '公告-编辑发布', 'API', 126, 'system', 'system')
ON CONFLICT (id) DO NOTHING;

-- 3.2 角色绑定：notice:view / notice:edit → ADMIN(2)
INSERT INTO sys_role_permission (id, role_id, permission_id, create_by, update_by)
SELECT 9000000000000400000 + p.id, 2, p.id, 'system', 'system'
FROM sys_permission p
WHERE p.deleted = 0
  AND p.code IN ('notice:view', 'notice:edit')
ON CONFLICT DO NOTHING;

-- 3.3 落地断言：权限码与角色授予齐备（id 被占用时在此显式失败，不静默放行）
DO
$$
    DECLARE
        missing_code  TEXT;
        missing_grant TEXT;
    BEGIN
        SELECT string_agg(want.code, ', ') INTO missing_code
        FROM (VALUES ('notice:view'), ('notice:edit')) AS want(code)
        WHERE NOT EXISTS (
            SELECT 1 FROM sys_permission p
            WHERE p.code = want.code AND p.deleted = 0
        );
        IF missing_code IS NOT NULL THEN
            RAISE EXCEPTION '[V151] 权限码缺失（id 206/207 可能已被占用）：%', missing_code;
        END IF;

        SELECT string_agg(want.code || ' -> role ' || want.role_id, ', ') INTO missing_grant
        FROM (VALUES ('notice:view', 2), ('notice:edit', 2)) AS want(code, role_id)
        WHERE NOT EXISTS (
            SELECT 1 FROM sys_permission p
            JOIN sys_role_permission rp ON rp.permission_id = p.id
            WHERE p.code = want.code AND p.deleted = 0 AND rp.role_id = want.role_id
        );
        IF missing_grant IS NOT NULL THEN
            RAISE EXCEPTION '[V151] 角色授予缺失：%', missing_grant;
        END IF;

        RAISE NOTICE '[V151] 公告管理权限点与角色授予齐备';
    END
$$;