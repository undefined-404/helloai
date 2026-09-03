package com.helloai.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.mq.service.MessageDeduplicationService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

@Slf4j
public abstract class AbstractIdempotentConsumer {

    protected final JdbcTemplate jdbcTemplate;
    protected final ObjectMapper objectMapper;
    protected final MessageDeduplicationService deduplicationService;

    private static final long SLOW_CONSUME_THRESHOLD_MS = 30000;

    /** MDC 键：子任务 ID（事件链追踪，与请求侧 {@code X-Task-Id} 对应层级一致）。 */
    public static final String MDC_SUB_TASK_ID = "sub_task_id";

    /** MDC 键：任务 ID（事件链追踪，与请求侧 {@code X-Task-Id} 对应）。 */
    public static final String MDC_TASK_ID = "task_id";

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

    /**
     * 幂等消费（携带 MDC 业务上下文）：进入前写入、退出时清理，异常路径同样不残留。
     *
     * <p>Phase 0 C4：子任务执行 / 核验等消费链在业务入口传入消息体中的业务标识
     * （如 {@link #MDC_SUB_TASK_ID}），使该线程的整段消费日志具备跨链路可追踪性。</p>
     */
    protected boolean tryConsume(String messageId, String consumerName, Map<String, String> mdcContext,
                                 Runnable consumerLogic) {
        if (mdcContext != null) {
            mdcContext.forEach(MDC::put);
        }
        try {
            return tryConsume(messageId, consumerName, consumerLogic);
        } finally {
            if (mdcContext != null) {
                mdcContext.keySet().forEach(MDC::remove);
            }
        }
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
