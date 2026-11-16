package com.helloai.it;

import com.helloai.core.system.announcement.AnnouncementService;
import com.helloai.core.system.announcement.PublishedAnnouncement;
import com.helloai.core.system.entity.Announcement;
import com.helloai.core.system.service.SysConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MSG-6：置顶公告「按版本已读」的真实 DB 证据（G-035，设计 §3.7/§3.10）。
 *
 * <p><b>为什么必须真实 DB</b>：seen_version 单调递增写在 {@code ON CONFLICT ... GREATEST} 里，
 * 工作副本与发布快照的二分、version+1 与唯一约束都落在 SQL/事务里，mock 无法证明。
 * 本类在 Testcontainers 空 PG 上跑完整迁移链（含 V151 消息+公告合并迁移）后，经真实
 * {@link AnnouncementService} 验证：</p>
 * <ul>
 *   <li>首发布 version=1 + 写 revision，fresh 用户未读;</li>
 *   <li>markSeen 后已读，且上报旧版本不回退 seen_version（GREATEST 单调）;</li>
 *   <li>编辑草稿不改线上快照，重新发布 version+1 → 全员重新未读;</li>
 *   <li>开关 OFF → getPublished=null / unreadCount=0;归档 → 不再展示;</li>
 *   <li>同 (user, announcement) 重复 markSeen → 一行（唯一约束 + upsert）。</li>
 * </ul>
 *
 * <p><b>用户归属</b>：announcement_seen.user_id 外键引用 sys_user，复用 V1 内建 admin
 * （{@code 1000000000000000001}）;公告 id 由雪花生成，{@code @AfterEach} 按表清场（先 seen→revision→announcement）。</p>
 */
@DisplayName("MSG-6: 置顶公告版本已读与开关停发")
class AnnouncementVersionReadIT extends AbstractItTestBase {

    private static final long ADMIN_USER_ID = 1000000000000000001L;

    @Autowired
    private AnnouncementService announcementService;

    @Autowired
    private SysConfigService sysConfigService;

    private long seedPublished(String title, String body) {
        sysConfigService.setValue("announcement.enabled", "1");
        announcementService.saveDraft(title, body);
        Announcement a = announcementService.getManageable();
        assertNotNull(a, "saveDraft 后应存在工作副本");
        announcementService.publish(ADMIN_USER_ID);
        return a.getId();
    }

    private int seenVersionInDb(long announcementId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT seen_version FROM announcement_seen WHERE user_id = ? AND announcement_id = ?",
                Integer.class, ADMIN_USER_ID, announcementId);
        return v == null ? 0 : v;
    }

    @Test
    @DisplayName("首发布 version=1 + 写 revision，fresh 用户未读")
    void firstPublishIsVersionOneAndUnread() {
        long annId = seedPublished("更新公告 v1", "正文 v1");

        PublishedAnnouncement p = announcementService.getPublished(ADMIN_USER_ID);
        assertNotNull(p, "开关 ON 且有已发布置顶时应返回快照");
        assertEquals(annId, p.id());
        assertEquals(1, p.version(), "首发布版本号必须是 1");
        assertEquals("更新公告 v1", p.title());
        assertEquals(0, p.seenVersion(), "fresh 用户已读位为 0");
        assertTrue(p.unread(), "seen_version < version 即未读");
        assertEquals(1L, announcementService.unreadCount(ADMIN_USER_ID));
        assertEquals(1, announcementService.history().size(), "首发布留一份 revision");
    }

    @Test
    @DisplayName("markSeen 后已读;上报旧版本不回退 seen_version（GREATEST 单调）")
    void markSeenIsReadAndMonotonic() {
        long annId = seedPublished("单调测试", "正文");

        announcementService.markSeen(ADMIN_USER_ID, annId, 1);
        PublishedAnnouncement p = announcementService.getPublished(ADMIN_USER_ID);
        assertNotNull(p);
        assertFalse(p.unread(), "上报当前版本后应已读");
        assertEquals(1, p.seenVersion());
        assertEquals(0L, announcementService.unreadCount(ADMIN_USER_ID));

        // 上报更旧版本：GREATEST 保持 seen_version=1，不回退
        announcementService.markSeen(ADMIN_USER_ID, annId, 0);
        assertEquals(1, seenVersionInDb(annId), "seen_version 必须单调递增，旧版本上报不得回退");
        assertFalse(announcementService.getPublished(ADMIN_USER_ID).unread());
    }

    @Test
    @DisplayName("编辑草稿不改线上快照;重新发布 version+1 → 重新未读")
    void draftEditDecoupledAndRepublishReUnreads() {
        long annId = seedPublished("线上标题", "线上正文");
        announcementService.markSeen(ADMIN_USER_ID, annId, 1);
        assertFalse(announcementService.getPublished(ADMIN_USER_ID).unread());

        // 编辑工作副本：线上仍展示上一版快照
        announcementService.saveDraft("草稿新标题", "草稿新正文");
        PublishedAnnouncement before = announcementService.getPublished(ADMIN_USER_ID);
        assertNotNull(before);
        assertEquals("线上标题", before.title(), "编辑草稿不得改变线上展示（取 revision 快照）");
        assertEquals(1, before.version());
        assertEquals("草稿新标题", announcementService.getManageable().getTitle(), "工作副本应已更新");

        // 重新发布：version+1，已读位 1 < 2 → 重新未读
        announcementService.publish(ADMIN_USER_ID);
        PublishedAnnouncement after = announcementService.getPublished(ADMIN_USER_ID);
        assertNotNull(after);
        assertEquals(2, after.version(), "重新发布版本号自增");
        assertEquals("草稿新标题", after.title(), "发布后线上快照更新为新内容");
        assertTrue(after.unread(), "新版本发布后已读用户重新未读");
        assertEquals(2, announcementService.history().size(), "每次发布各留一份 revision");
    }

    @Test
    @DisplayName("开关 OFF：getPublished=null / unreadCount=0（停发 + 隐藏）")
    void disabledToggleHidesPublished() {
        seedPublished("开关测试", "正文");
        assertNotNull(announcementService.getPublished(ADMIN_USER_ID));

        sysConfigService.setValue("announcement.enabled", "0");
        assertNull(announcementService.getPublished(ADMIN_USER_ID), "开关 OFF 读取返回空");
        assertEquals(0L, announcementService.unreadCount(ADMIN_USER_ID), "开关 OFF 未读恒 0");
    }

    @Test
    @DisplayName("归档后不再展示（getPublished=null）")
    void archiveHidesPublished() {
        seedPublished("归档测试", "正文");
        assertNotNull(announcementService.getPublished(ADMIN_USER_ID));

        announcementService.archive();
        assertNull(announcementService.getPublished(ADMIN_USER_ID), "归档态不在置顶查询命中");
    }

    @Test
    @DisplayName("同 (user, announcement) 重复 markSeen → 一行（唯一约束 + upsert）")
    void repeatedMarkSeenKeepsSingleRow() {
        long annId = seedPublished("幂等测试", "正文");

        announcementService.markSeen(ADMIN_USER_ID, annId, 1);
        announcementService.markSeen(ADMIN_USER_ID, annId, 1);

        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM announcement_seen WHERE user_id = ? AND announcement_id = ?",
                Long.class, ADMIN_USER_ID, annId);
        assertEquals(1L, n == null ? 0 : n, "重复上报已读必须只落一行");
    }

    @AfterEach
    void cleanup() {
        // 外键顺序：seen → revision → announcement
        jdbcTemplate.update("DELETE FROM announcement_seen");
        jdbcTemplate.update("DELETE FROM announcement_revision");
        jdbcTemplate.update("DELETE FROM announcement");
        sysConfigService.setValue("announcement.enabled", "0");
    }
}