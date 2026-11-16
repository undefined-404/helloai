package com.helloai.core.system.announcement;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.system.entity.Announcement;
import com.helloai.core.system.entity.AnnouncementRevision;

import java.util.List;

/**
 * 置顶公告服务（MSG-6，设计 §3.7/§3.10）。
 *
 * <p>单条置顶 + 版本已读：管理侧维护唯一一条工作副本（草稿/发布/归档/历史），每次发布
 * {@code version+1} 并留全量快照;读取侧 {@link #getPublished} 渲染最新已发布快照 + 当前用户
 * 已读位。已读 = {@code seen_version >= version}，发布新版本即全员重新未读。</p>
 *
 * <p><b>平台开关</b>：{@code announcement.enabled} 默认 OFF。OFF 只隐藏读取与展示
 * （{@link #getPublished} 返回 null、{@link #unreadCount} 返回 0）;公告写入（人工撰写）不受限制
 * （设计 §3.10）。</p>
 */
public interface AnnouncementService extends IService<Announcement> {

    /** 管理侧：唯一一条工作副本（无则返回 null，GET 无副作用）。 */
    Announcement getManageable();

    /** 管理侧：保存草稿（无工作副本则建 DRAFT，有则更新 title/body;不改变线上展示）。 */
    void saveDraft(String title, String body);

    /** 管理侧：发布工作副本（version+1 + 写 revision + 置 PUBLISHED），返回发布后的公告。 */
    Announcement publish(Long operatorId);

    /** 管理侧：归档（置 ARCHIVED，顶部条不再展示）。 */
    void archive();

    /** 管理侧：发布历史快照（按版本倒序）。 */
    List<AnnouncementRevision> history();

    /** 管理侧：迁移历史只读辅助视图（公告撰写素材）。 */
    List<SchemaHistoryRow> schemaHistory();

    /** 读取侧：当前置顶公告 + 我的已读位。开关 OFF / 无已发布 → null。 */
    PublishedAnnouncement getPublished(Long userId);

    /** 读取侧：上报已读版本（幂等，只单调递增;归属未识别 fail-close）。 */
    void markSeen(Long userId, Long announcementId, Integer version);

    /** 读取侧：公告未读数（并入铃铛角标）。开关 OFF → 0;有已发布且 seen<version → 1，否则 0。 */
    long unreadCount(Long userId);
}