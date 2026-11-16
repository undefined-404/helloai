package com.helloai.api.dto.system;

/**
 * 站内消息 / 置顶公告平台开关态（MSG-5，设计 §3.10）。
 *
 * <p>登录可读，供前端闸门用：铃铛闸门 {@code isAdmin && notificationEnabled}，
 * 公告条闸门 {@code announcementEnabled}，均默认 OFF（键缺省即关闭）。</p>
 */
public record NotificationFeatures(boolean notificationEnabled, boolean announcementEnabled) {
}