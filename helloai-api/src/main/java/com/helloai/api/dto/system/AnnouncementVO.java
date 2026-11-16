package com.helloai.api.dto.system;

import com.helloai.core.system.entity.Announcement;

import java.time.OffsetDateTime;

/**
 * 公告管理工作副本投影（MSG-6）。Long id → String;不含 receiver 等无关字段。
 */
public record AnnouncementVO(String id, String title, String body, String status, Integer pinned,
                             Integer version, OffsetDateTime publishedAt,
                             OffsetDateTime createTime, OffsetDateTime updateTime) {

    public static AnnouncementVO from(Announcement a) {
        return new AnnouncementVO(String.valueOf(a.getId()), a.getTitle(), a.getBody(), a.getStatus(),
                a.getPinned(), a.getVersion(), a.getPublishedAt(), a.getCreateTime(), a.getUpdateTime());
    }
}