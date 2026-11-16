package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 公告按版本已读位（MSG-6，表 {@code announcement_seen}，设计 §3.7）。
 *
 * <p>已读 = {@code seen_version >= version}（不是布尔位）;{@code (user_id, announcement_id)} 唯一，
 * {@code seen_version} 只单调递增（上报旧版本不回退）。管理员发布新版本后，全员 seen_version
 * 落后于 version ⇒ 重新未读，正是「打开项目第一时间看到最近更新了什么」。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("announcement_seen")
public class AnnouncementSeen extends BaseEntity {

    /** 用户 sys_user.id。 */
    private Long userId;

    /** 公告 id。 */
    private Long announcementId;

    /** 已读到的版本号（单调递增）。 */
    private Integer seenVersion;

    /** 最近上报时间。 */
    private OffsetDateTime seenAt;
}