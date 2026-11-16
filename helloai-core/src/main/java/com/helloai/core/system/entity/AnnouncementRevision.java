package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 公告发布快照（MSG-6，表 {@code announcement_revision}，设计 §3.7）。
 *
 * <p>每次「发布」留一份全量快照（编辑历史，不可改写）;{@code (announcement_id, version)} 唯一。
 * 顶部公告条与「按版本判已读」均以本快照为准，而非工作副本。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("announcement_revision")
public class AnnouncementRevision extends BaseEntity {

    /** 所属公告 id。 */
    private Long announcementId;

    /** 发布版本号。 */
    private Integer version;

    /** 发布时标题快照。 */
    private String title;

    /** 发布时正文快照。 */
    private String body;

    /** 发布人 sys_user.id（字符串留痕）。 */
    private String publishedBy;

    /** 发布时间。 */
    private OffsetDateTime publishedAt;
}