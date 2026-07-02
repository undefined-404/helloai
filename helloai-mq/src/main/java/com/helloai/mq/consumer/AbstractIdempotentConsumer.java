package com.helloai.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.mq.service.MessageDeduplicationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
public abstract class AbstractIdempotentConsumer {

    protected final JdbcTemplate jdbcTemplate;
    protected final ObjectMapper objectMapper;
    protected final MessageDeduplicationService deduplicationService;

    private static final long SLOW_CONSUME_THRESHOLD_MS = 30000;

    public AbstractIdempotentConsumer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                       MessageDeduplicationService deduplicationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.deduplicationService = deduplicationService;
    }

    public AbstractIdempotentConsumer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.deduplicationService = null;
    }

    protected boolean tryConsume(String messageId, String consumerName, Runnable consumerLogic) {
        if (deduplicationService != null) {
            return tryConsumeEnhanced(messageId, consumerName, consumerLogic);
        }
        return tryConsumeBasic(messageId, consumerName, consumerLogic);
    }

    private boolean tryConsumeEnhanced(String messageId, String consumerName, Runnable consumerLogic) {
        if (deduplicationService.isDuplicate(messageId)) {
            log.info("幂等跳过: messageId={}, consumer={}", messageId, consumerName);
            return true;
        }

        long startTime = System.currentTimeMillis();
        try {
            consumerLogic.run();
            deduplicationService.markConsumed(messageId, consumerName);

            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > SLOW_CONSUME_THRESHOLD_MS) {
                log.warn("消费耗时过长: messageId={}, consumer={}, elapsed={}ms", messageId, consumerName, elapsed);
            } else {
                log.info("消费完成: messageId={}, consumer={}, elapsed={}ms", messageId, consumerName, elapsed);
            }
            return true;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("消费失败: messageId={}, consumer={}, elapsed={}ms", messageId, consumerName, elapsed, e);
            deduplicationService.markFailed(messageId);
            throw e;
        }
    }

    private boolean tryConsumeBasic(String messageId, String consumerName, Runnable consumerLogic) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM event_consumption_log WHERE message_id = ? AND consumer = ?",
                Integer.class, messageId, consumerName);
        if (count != null && count > 0) {
            log.info("Message {} already consumed by {}, skipping", messageId, consumerName);
            return true;
        }

        long startTime = System.currentTimeMillis();
        try {
            consumerLogic.run();
            jdbcTemplate.update(
                    "INSERT INTO event_consumption_log (id, message_id, consumer, status, create_by, update_by, create_time, update_time) " +
                    "VALUES (?, ?, ?, 'CONSUMED', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    System.nanoTime(), messageId, consumerName);

            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > SLOW_CONSUME_THRESHOLD_MS) {
                log.warn("消费耗时过长: messageId={}, consumer={}, elapsed={}ms", messageId, consumerName, elapsed);
            } else {
                log.info("消费完成: messageId={}, consumer={}, elapsed={}ms", messageId, consumerName, elapsed);
            }
            return true;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Failed to consume message {} by {}, elapsed={}ms", messageId, consumerName, elapsed, e);
            return false;
        }
    }
}
