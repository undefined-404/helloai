package com.helloai.core.planner.assistant.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 会话上下文压缩状态（PA-12，V136）。
 *
 * <p>记录「上下文窗口」的压缩进度：{@code compactedUntilSeq} 之前的消息已折叠进
 * {@code summary}（原文仍在 {@code requirement_message}，可回查）；阈值与保留尾比例可按会话覆盖。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("conversation_context_state")
public class ConversationContextState extends BaseEntity {

    /** 所属会话 ID（逻辑唯一）。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long conversationId;

    /** seq <= 该值的消息已折叠进 summary。 */
    private Integer compactedUntilSeq;

    /** 滚动摘要（压缩后的快速参考）。 */
    private String summary;

    /** 摘要版本（每次压缩 +1）。 */
    private Integer summaryVersion;

    /** 自动压缩阈值比例（总 token / context_size）。 */
    private BigDecimal triggerRatio;

    /** 保留尾部比例（最近消息至少保留的 token 占比）。 */
    private BigDecimal reserveRatio;

    /** 是否启用自动压缩（1/0）。 */
    private Integer enabled;

    /** 模型上下文窗口快照（null = 用全局配置）。 */
    private Integer contextSize;

    /** 上一次模型调用返回的 token 用量（校准参考）。 */
    private Integer lastTokenUsage;

    /** 归属用户 id（null = 老数据 / 未识别）。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerId;

    /** 最近一次压缩时间。 */
    private OffsetDateTime lastCompactAt;
}