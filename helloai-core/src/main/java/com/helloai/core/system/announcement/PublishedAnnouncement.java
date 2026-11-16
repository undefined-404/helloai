package com.helloai.core.system.announcement;

import java.time.OffsetDateTime;

/**
 * 当前置顶公告读模型（MSG-6，设计 §3.7/§3.9）。
 *
 * <p>顶部公告条渲染内容取「最新已发布快照」（title/body/version 来自 revision），
 * 而非管理员工作副本;{@code seenVersion} 为当前用户已读位，{@code unread = seenVersion < version}。
 * 开关 OFF / 无已发布公告时服务层返回 {@code null}。</p>
 */
public record PublishedAnnouncement(Long id, String title, String body, Integer version,
                                    OffsetDateTime publishedAt, Integer seenVersion, boolean unread) {
}