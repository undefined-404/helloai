package com.helloai.core.system.announcement;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 迁移历史只读投影（MSG-6，公告撰写辅助视图，设计 §3.7）。
 *
 * <p>数据源 {@code flyway_schema_history}，供管理员撰写公告时参考「本库做过哪些结构变更」;
 * 仅作素材，不自动发布、不把 DB 变更当公告（设计 §2.3）。</p>
 */
@Data
public class SchemaHistoryRow {

    /** 迁移版本号。 */
    private String version;

    /** 迁移描述。 */
    private String description;

    /** 安装时间。 */
    private OffsetDateTime installedOn;

    /** 是否成功。 */
    private Boolean success;
}