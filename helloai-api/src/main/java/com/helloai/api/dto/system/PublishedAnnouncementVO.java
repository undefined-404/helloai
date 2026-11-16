package com.helloai.api.dto.system;

import com.helloai.core.system.announcement.PublishedAnnouncement;

import java.time.OffsetDateTime;

/**
 * 当前置顶公告投影（MSG-6，顶部公告条用）。Long id → String;内容取最新已发布快照。
 */
public record PublishedAnnouncementVO(String id, String title, String body, Integer version,
                                      OffsetDateTime publishedAt, Integer seenVersion, boolean unread) {

    public static PublishedAnnouncementVO from(PublishedAnnouncement p) {
        return new PublishedAnnouncementVO(String.valueOf(p.id()), p.title(), p.body(), p.version(),
                p.publishedAt(), p.seenVersion(), p.unread());
    }
}