package com.helloai.core.dlx;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * A4 S2：死信台账（{@code mq_dead_letter_archive}）查询视图行。
 *
 * <p>非表实体：台账由 {@link DlxAlertConsumer} 用 JdbcTemplate 直写（V60 无 MyBatis 实体），
 * 本行对象仅承载人工恢复入口的窗口分页查询与重放读取。{@code headers} 列（JSONB）
 * 以文本返回，重放时按需解析 content-type，取不到回落默认 JSON。</p>
 */
@Data
public class MqDeadLetterArchiveRow {

    /** 台账主键（BIGSERIAL 自增）。 */
    private Long id;

    /** 死信原始发布 exchange。 */
    private String originalExchange;

    /** 死信原始 routing key（重放按此 + 原 exchange 重发）。 */
    private String originalRoutingKey;

    /** x-first-death-exchange 头。 */
    private String firstDeathExchange;

    /** x-first-death-queue 头。 */
    private String firstDeathQueue;

    /** x-first-death-reason 头（如 rejected / expired）。 */
    private String firstDeathReason;

    /** 完整消息头快照（JSONB 文本）。 */
    private String headers;

    /** 消息体快照（原始 JSON 文本，超长截断至 64KB）。 */
    private String body;

    /** 第一次进入死信台账的时间。 */
    private OffsetDateTime createdAt;

    /** 重放回填时间；非空即表示已重放（CAS 幂等依据）。 */
    private OffsetDateTime replayedAt;
}