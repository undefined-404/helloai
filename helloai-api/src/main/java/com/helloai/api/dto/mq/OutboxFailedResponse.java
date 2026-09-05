package com.helloai.api.dto.mq;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * A4 S1：outbox FAILED 行视图（mq-recovery 人工恢复列表接口返回）。
 *
 * <p>仅承载运维挑选所需字段：行标识、事件标识、失败原因、计数与时间。
 * 不承载 payload 明细（载荷可直接查 DB）。</p>
 */
@Data
public class OutboxFailedResponse {

    private Long id;

    /** 消息唯一标识（与 ExecutionCommand.eventId 对齐）。 */
    private String eventId;

    /** 业务聚合根 ID（agent_execution_record.id）。 */
    private String aggregateId;

    /** 失败前已重试次数。 */
    private Integer retryCount;

    /** 最后一次失败原因。 */
    private String errorMsg;

    /** 创建时间。 */
    private OffsetDateTime createTime;
}