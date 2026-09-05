package com.helloai.core.dlx.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.core.dlx.DeadLetterRecoveryService;
import com.helloai.core.dlx.MqDeadLetterArchiveRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

/**
 * A4 S2：死信台账人工恢复服务实现。
 *
 * <p>重放链路（对应执行方案 §3.2）：
 * <pre>
 *   replay(id)
 *     ├─ 台账行不存在 → BizException（fail-close）
 *     ├─ replayed_at 非空 → BizException「已重放」冲突（CAS 幂等）
 *     ├─ body 取不到 eventId → BizException「无法识别消息标识，禁止重放」（不做盲发）
 *     ├─ 重置去重台账（D-A4-5）：DELETE event_consumption_log WHERE message_id=?
 *     │    + DEL redis key mq:dedup:{messageId}
 *     ├─ RabbitTemplate.send(original_exchange, original_routing_key, body) 显式字节重发
 *     │    发送异常 → 不写 replayed_at、抛 BizException（仍可再次重放，无状态污染）
 *     └─ 发送成功 → CAS UPDATE replayed_at=now() WHERE id=? AND replayed_at IS NULL
 *                    （并发双点重放只生效一次）
 * </pre>
 *
 * <p>去重键前缀 {@code mq:dedup:} 与
 * {@code com.helloai.mq.service.impl.MessageDeduplicationServiceImpl} 严格对齐
 * （Redis + DB 双层去重的另一条腿，重置必须两腿同删）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterRecoveryServiceImpl implements DeadLetterRecoveryService {

    /** Redis 去重键前缀（与 MessageDeduplicationServiceImpl.DEDUP_KEY_PREFIX 对齐）。 */
    private static final String DEDUP_KEY_PREFIX = "mq:dedup:";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    private static final RowMapper<MqDeadLetterArchiveRow> ROW_MAPPER = (rs, rowNum) -> {
        MqDeadLetterArchiveRow row = new MqDeadLetterArchiveRow();
        row.setId(rs.getLong("id"));
        row.setOriginalExchange(rs.getString("original_exchange"));
        row.setOriginalRoutingKey(rs.getString("original_routing_key"));
        row.setFirstDeathExchange(rs.getString("first_death_exchange"));
        row.setFirstDeathQueue(rs.getString("first_death_queue"));
        row.setFirstDeathReason(rs.getString("first_death_reason"));
        row.setHeaders(rs.getString("headers"));
        row.setBody(rs.getString("body"));
        row.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
        row.setReplayedAt(rs.getObject("replayed_at", OffsetDateTime.class));
        return row;
    };

    @Override
    public IPage<MqDeadLetterArchiveRow> listArchive(OffsetDateTime from, OffsetDateTime to,
                                                     boolean includeReplayed, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.max(size, 1);
        String replayFilter = includeReplayed ? "" : " AND replayed_at IS NULL";
        long total = countArchive(from, to, includeReplayed);
        Page<MqDeadLetterArchiveRow> result = new Page<>(safePage, safeSize, total);
        if (total == 0) {
            result.setRecords(Collections.emptyList());
            return result;
        }
        List<MqDeadLetterArchiveRow> rows = jdbcTemplate.query(
                "SELECT id, original_exchange, original_routing_key, first_death_exchange, " +
                        "first_death_queue, first_death_reason, headers::text AS headers, body, " +
                        "created_at, replayed_at " +
                        "FROM mq_dead_letter_archive " +
                        "WHERE created_at >= ? AND created_at <= ?" + replayFilter +
                        " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, from, to, safeSize, (safePage - 1) * safeSize);
        result.setRecords(rows);
        return result;
    }

    @Override
    public void replay(long id) {
        MqDeadLetterArchiveRow row = findById(id);
        if (row == null) {
            throw new BizException("死信台账不存在: id=" + id);
        }
        if (row.getReplayedAt() != null) {
            throw new BizException("该死信已重放，禁止重复重放: id=" + id);
        }

        String messageId = extractMessageId(row.getBody());
        if (messageId == null) {
            throw new BizException("无法识别消息标识（eventId），禁止重放: id=" + id);
        }

        // 1) 先重置去重台账（D-A4-5）：失败记录删除后，重放消息可重新走完整消费链
        jdbcTemplate.update("DELETE FROM event_consumption_log WHERE message_id = ?", messageId);
        redisTemplate.delete(DEDUP_KEY_PREFIX + messageId);

        // 2) 按原 exchange / routingKey 重发（显式字节，依赖 SimpleMessageConverter 会发不出去）
        try {
            rabbitTemplate.send(row.getOriginalExchange(), row.getOriginalRoutingKey(), buildMessage(row));
        } catch (Exception e) {
            log.error("死信重放发送失败（replayed_at 未写，可再次重放）: id={}, messageId={}", id, messageId, e);
            throw new BizException("死信重放发送失败: id=" + id);
        }

        // 3) 发送成功 → CAS 写 replayed_at（并发双点重放只生效一次）
        int updated = jdbcTemplate.update(
                "UPDATE mq_dead_letter_archive SET replayed_at = now() WHERE id = ? AND replayed_at IS NULL",
                id);
        if (updated == 0) {
            throw new BizException("死信已被并发重放（replayed_at CAS 冲突）: id=" + id);
        }
        log.info("死信重放完成: id={}, messageId={}", id, messageId);
    }

    private long countArchive(OffsetDateTime from, OffsetDateTime to, boolean includeReplayed) {
        String sql = includeReplayed
                ? "SELECT COUNT(1) FROM mq_dead_letter_archive WHERE created_at >= ? AND created_at <= ?"
                : "SELECT COUNT(1) FROM mq_dead_letter_archive WHERE created_at >= ? AND created_at <= ? " +
                        "AND replayed_at IS NULL";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, from, to);
        return count == null ? 0L : count;
    }

    private MqDeadLetterArchiveRow findById(long id) {
        List<MqDeadLetterArchiveRow> rows = jdbcTemplate.query(
                "SELECT id, original_exchange, original_routing_key, first_death_exchange, " +
                        "first_death_queue, first_death_reason, headers::text AS headers, body, " +
                        "created_at, replayed_at FROM mq_dead_letter_archive WHERE id = ?",
                ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 从消息体快照解析 eventId（ExecutionCommandMqMessage 载荷）；取不到返回 null（调用方 fail-close）。 */
    private String extractMessageId(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode eventId = node.get("eventId");
            String id = (eventId == null || eventId.isNull()) ? null : eventId.asText();
            return (id == null || id.isBlank()) ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    /** 组重发消息：content-type 从 headers 快照回放（取不到默认 JSON），显式字节体。 */
    private Message buildMessage(MqDeadLetterArchiveRow row) {
        MessageProperties props = new MessageProperties();
        props.setContentType(resolveContentType(row.getHeaders()));
        return new Message(row.getBody().getBytes(StandardCharsets.UTF_8), props);
    }

    private String resolveContentType(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return MessageProperties.CONTENT_TYPE_JSON;
        }
        try {
            JsonNode node = objectMapper.readTree(headersJson);
            JsonNode contentType = node.get("contentType");
            if (contentType == null) {
                contentType = node.get("content_type");
            }
            if (contentType != null && contentType.isTextual() && !contentType.asText().isBlank()) {
                return contentType.asText();
            }
        } catch (Exception e) {
            log.warn("死信 headers 快照解析失败，content-type 回落默认 JSON", e);
        }
        return MessageProperties.CONTENT_TYPE_JSON;
    }
}