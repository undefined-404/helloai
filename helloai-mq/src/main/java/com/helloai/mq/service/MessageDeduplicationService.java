package com.helloai.mq.service;

/**
 * MQ 消息去重服务。
 *
 * <p>Redis 布隆式幂等键 + DB {@code event_consumption_log} 双通道去重：
 * 消费前 {@link #isDuplicate} 判重，消费成功 {@link #markConsumed} 落幂等记录，
 * 消费失败 {@link #markFailed} 记失败态（不回滚幂等键）。</p>
 */
public interface MessageDeduplicationService {

    /**
     * 判断消息是否重复（Redis 命中即重复；DB 有消费记录则回填 Redis 后判重）。
     */
    boolean isDuplicate(String messageId);

    /**
     * 标记消息已消费（Redis 写幂等键 + DB 落 {@code event_consumption_log} 幂等行）。
     */
    void markConsumed(String messageId, String consumerGroup);

    /**
     * 标记消息消费失败（DB 记 FAILED 行，幂等键不写，允许后续重试）。
     */
    void markFailed(String messageId);
}
