package com.helloai.api.dto.system;

import com.helloai.core.system.entity.UserNotification;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 人侧站内消息行投影（MSG-5，设计 §3.9）。
 *
 * <p>Long ID 一律转字符串（前端 LongId 契约，避免 Number 精度丢失，对齐 AdminInboxController.toRow）。
 * 不含 receiverId——读取端点恒按当前登录用户过滤，前端无需也不应拿到他人归属。</p>
 */
public record NotificationVO(
        String id,
        String type,
        String severity,
        String title,
        String summary,
        String refType,
        String refId,
        Map<String, Object> payload,
        Integer isRead,
        OffsetDateTime createTime,
        OffsetDateTime readAt) {

    public static NotificationVO from(UserNotification n) {
        return new NotificationVO(
                String.valueOf(n.getId()),
                n.getType(),
                n.getSeverity(),
                n.getTitle(),
                n.getSummary(),
                n.getRefType(),
                n.getRefId() != null ? String.valueOf(n.getRefId()) : null,
                n.getPayload(),
                n.getIsRead(),
                n.getCreateTime(),
                n.getReadAt());
    }
}