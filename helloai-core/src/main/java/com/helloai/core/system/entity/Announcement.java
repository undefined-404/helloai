package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 置顶公告工作副本（MSG-6，表 {@code announcement}，设计 §3.7/§3.8）。
 *
 * <p>单条置顶语义：{@code title}/{@code body} 是管理员可编辑的「工作副本」;线上顶部条渲染的是
 * 最新已发布快照（{@link AnnouncementRevision}），故编辑草稿不改变线上展示，须再次发布才生效。
 * {@code version} 计发布次数（首发布为 1），是「按版本判已读」的唯一依据。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("announcement")
public class Announcement extends BaseEntity {

    /** 标题（工作副本）。 */
    private String title;

    /** 正文（工作副本）。 */
    private String body;

    /** 状态：DRAFT / PUBLISHED / ARCHIVED。 */
    private String status;

    /** 是否顶部展示：0-否，1-是（第一期单条置顶恒 1）。 */
    private Integer pinned;

    /** 最近一次发布的版本号（每次发布自增）。 */
    private Integer version;

    /** 最近发布时间。 */
    private OffsetDateTime publishedAt;
}