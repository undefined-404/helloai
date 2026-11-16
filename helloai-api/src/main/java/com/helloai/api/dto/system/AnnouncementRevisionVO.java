package com.helloai.api.dto.system;

import com.helloai.core.system.entity.AnnouncementRevision;

import java.time.OffsetDateTime;

/**
 * 公告发布历史快照投影（MSG-6）。Long id → String。
 */
public record AnnouncementRevisionVO(String id, Integer version, String title, String body,
                                     String publishedBy, OffsetDateTime publishedAt) {

    public static AnnouncementRevisionVO from(AnnouncementRevision r) {
        return new AnnouncementRevisionVO(String.valueOf(r.getId()), r.getVersion(), r.getTitle(),
                r.getBody(), r.getPublishedBy(), r.getPublishedAt());
    }
}