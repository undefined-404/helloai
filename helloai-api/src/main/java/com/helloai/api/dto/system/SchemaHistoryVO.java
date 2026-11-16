package com.helloai.api.dto.system;

import com.helloai.core.system.announcement.SchemaHistoryRow;

import java.time.OffsetDateTime;

/**
 * 迁移历史辅助视图投影（MSG-6，公告撰写素材）。
 */
public record SchemaHistoryVO(String version, String description, OffsetDateTime installedOn, Boolean success) {

    public static SchemaHistoryVO from(SchemaHistoryRow r) {
        return new SchemaHistoryVO(r.getVersion(), r.getDescription(), r.getInstalledOn(), r.getSuccess());
    }
}