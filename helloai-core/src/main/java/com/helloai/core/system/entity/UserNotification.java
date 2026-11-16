package com.helloai.core.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.helloai.common.base.BaseEntity;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 人侧站内消息（MSG-2，表 {@code user_notification}，设计 §3.8）。
 *
 * <p>owner-scoped 布尔已读（{@code is_read}/{@code is_archived}），无状态机——有效性判深链回源，
 * 不判消息状态（设计 §3.4）。{@code dedupe_key} + {@code COALESCE(receiver_id,0)} 唯一索引是
 * 跨副本幂等最终防线；{@code receiver_id} 为 NULL 表示广播。</p>
 *
 * <p>{@code payload} 为 JSONB，仅作列表行摘要，不作事实依据；强制 {@link PgJsonbTypeHandler}
 * （CODE_STYLE §16.3，禁用 MyBatis-Plus JacksonTypeHandler）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "user_notification", autoResultMap = true)
public class UserNotification extends BaseEntity {

    /** 归属人 sys_user.id；NULL = 广播。 */
    private Long receiverId;

    /** 消息类型（NotificationType 枚举名）。 */
    private String type;

    /** 严重度：NORMAL / HIGH / URGENT。 */
    private String severity;

    /** 标题（消息域渲染）。 */
    private String title;

    /** 摘要（消息域渲染，列表行用）。 */
    private String summary;

    /** 幂等键：{type}:{事件码}:{实例} 或 {type}:{sourceType}:{sourceId}:{instanceKey}。 */
    private String dedupeKey;

    /** 深链归属类型：TASK / SUB_TASK / CONVERSATION / AGENT / RELEASE。 */
    private String refType;

    /** 深链业务对象 id。 */
    private Long refId;

    /** 渲染数据（JSONB），仅作列表摘要，不作事实依据。 */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> payload;

    /** 是否已读：0-未读，1-已读。 */
    private Integer isRead;

    /** 阅读时间。 */
    private OffsetDateTime readAt;

    /** 是否已归档：0-否，1-是。 */
    private Integer isArchived;

    /** 过期时间（超过此时间未读可被清理；NULL = 不过期）。 */
    private OffsetDateTime expiresAt;
}