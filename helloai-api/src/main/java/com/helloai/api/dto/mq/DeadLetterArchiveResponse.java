package com.helloai.api.dto.mq;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * A4 S2：死信台账行视图（mq-recovery 台账列表接口返回）。
 *
 * <p>承载人工判断重放所需的溯源信息：原始投递坐标（exchange/routingKey）、
 * 首次死亡原因、消息体快照与时间戳；{@code replayedAt} 非空即已重放。</p>
 */
@Data
public class DeadLetterArchiveResponse {

    private Long id;

    /** 死信原始发布 exchange。 */
    private String originalExchange;

    /** 死信原始 routing key。 */
    private String originalRoutingKey;

    /** x-first-death-reason 头（如 rejected / expired）。 */
    private String firstDeathReason;

    /** 消息体快照（原始文本，超长截断至 64KB）。 */
    private String body;

    /** 第一次进入死信台账的时间。 */
    private OffsetDateTime createdAt;

    /** 重放回填时间；非空即已重放。 */
    private OffsetDateTime replayedAt;
}