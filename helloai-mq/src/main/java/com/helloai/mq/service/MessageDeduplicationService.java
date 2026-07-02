package com.helloai.mq.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
public class MessageDeduplicationService {

    private static final String DEDUP_KEY_PREFIX = "mq:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public MessageDeduplicationService(StringRedisTemplate redisTemplate, JdbcTemplate jdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isDuplicate(String messageId) {
        String redisKey = DEDUP_KEY_PREFIX + messageId;
        Boolean exists = redisTemplate.hasKey(redisKey);
        if (Boolean.TRUE.equals(exists)) {
            log.debug("消息重复(Redis命中): messageId={}", messageId);
            return true;
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM event_consumption_log WHERE message_id = ? AND deleted = 0",
                Integer.class, messageId);
        if (count != null && count > 0) {
            redisTemplate.opsForValue().set(redisKey, "1", DEDUP_TTL);
            log.debug("消息重复(DB命中，回填Redis): messageId={}", messageId);
            return true;
        }

        return false;
    }

    public void markConsumed(String messageId, String consumerGroup) {
        String redisKey = DEDUP_KEY_PREFIX + messageId;
        redisTemplate.opsForValue().set(redisKey, "1", DEDUP_TTL);

        try {
            jdbcTemplate.update(
                    "INSERT INTO event_consumption_log (id, message_id, consumer, status, create_by, update_by, create_time, update_time) " +
                    "VALUES (?, ?, ?, 'CONSUMED', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                    "ON CONFLICT (message_id) DO NOTHING",
                    System.nanoTime(), messageId, consumerGroup);
        } catch (Exception e) {
            log.debug("消费记录已存在(幂等): messageId={}", messageId);
        }

        log.debug("消息已标记消费: messageId={}, group={}", messageId, consumerGroup);
    }

    public void markFailed(String messageId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO event_consumption_log (id, message_id, consumer, status, create_by, update_by, create_time, update_time) " +
                    "VALUES (?, ?, 'FAILED', 'FAILED', 'system', 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) " +
                    "ON CONFLICT (message_id) DO NOTHING",
                    System.nanoTime(), messageId);
        } catch (Exception e) {
            // ignore
        }
    }
}
