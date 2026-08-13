package com.helloai.core.review.mqconsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.mq.config.RabbitMQConfig;
import com.helloai.mq.consumer.AbstractIdempotentConsumer;
import com.helloai.mq.service.MessageDeduplicationService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 批次 D（§6.82）：REVIEWER 自动审查 L2 MQ consumer（三级容错 L2 补齐）。
 *
 * <p>审查链三级容错（§6.40 架构）：</p>
 * <ul>
 *     <li>L1：{@code SubTaskSubmittedForReviewEvent} AFTER_COMMIT + @Async 主路径
 *         （{@link SubTaskReviewService#onSubmittedForReview}）</li>
 *     <li>L2：MQ {@code agent.reviewer.assigned} → {@code reviewerQueue}（本类），
 *         L1 事件链因线程池/异常丢失时由 Outbox 补偿投递补位</li>
 *     <li>L3：{@code scanReviewOrphans} @Scheduled DB 孤儿扫描兜底</li>
 * </ul>
 *
 * <p>消费骨架与 {@code MqExecutionCommandConsumer} 同款约束：</p>
 * <ol>
 *     <li>仅在 {@code helloai.mq.review.consumer-enabled=true} 时生效（yml 默认开启），
 *         关闭后整个 Bean 不存在，不影响 L1 / L3 路径</li>
 *     <li>消息体为 {@code AgentOutboxService.createEvent} 的 payload JSON
 *         （eventId / subTaskId / taskId / status / agentId）；解析失败或缺 subTaskId 按 ACK 处理</li>
 *     <li>MANUAL ACK：消费成功 → basicAck；消费失败 → basicNack(requeue=false) 走 DLX</li>
 *     <li>幂等由父类 {@link AbstractIdempotentConsumer#tryConsume} 提供 Redis + DB 双层去重：
 *         幂等键优先取 payload.eventId（§6.82 生产侧补充，同一事件重投不重复消费，
 *         同子任务多轮 REVIEW 各自独立）；老消息无 eventId 时回退 {@code sub_task.review:{subTaskId}}</li>
 *     <li>与 L3 冲突防重：核验本身由 {@link SubTaskReviewService#reviewSubTask} 的
 *         Redis 互斥锁 + 状态防重（非 REVIEW 跳过）双保险，本类无需额外防重逻辑</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "helloai.mq.review.consumer-enabled", havingValue = "true")
public class MqReviewCommandConsumer extends AbstractIdempotentConsumer {

    /** 消费者名称（用于幂等日志与 event_consumption_log 的 consumer 字段）。 */
    static final String CONSUMER_NAME = "MqReviewCommandConsumer";

    /** 老消息（payload 无 eventId）的幂等键前缀：sub_task.review:{subTaskId} */
    static final String LEGACY_ID_PREFIX = "sub_task.review:";

    private final SubTaskReviewService subTaskReviewService;

    public MqReviewCommandConsumer(JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper,
                                   MessageDeduplicationService deduplicationService,
                                   SubTaskReviewService subTaskReviewService) {
        super(jdbcTemplate, objectMapper, deduplicationService);
        this.subTaskReviewService = subTaskReviewService;
    }

    /**
     * RabbitMQ 入口：解析 payload → 幂等 → 触发核验 → ACK / NACK。
     *
     * <p>解析失败或缺 subTaskId 时直接 ACK（坏消息不阻塞队列，写死信也无意义）。</p>
     */
    @RabbitListener(queues = RabbitMQConfig.REVIEWER_QUEUE, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        Map<?, ?> payload;
        try {
            payload = objectMapper.readValue(message.getBody(), Map.class);
        } catch (Exception e) {
            log.warn("MQ 核验命令消息解析失败，跳过(ACK): body={}, error={}",
                    new String(message.getBody(), StandardCharsets.UTF_8), e.getMessage());
            channel.basicAck(tag, false);
            return;
        }
        if (payload == null) {
            channel.basicAck(tag, false);
            return;
        }
        Long subTaskId = toLong(payload.get("subTaskId"));
        if (subTaskId == null) {
            log.warn("MQ 核验命令缺少 subTaskId，跳过(ACK): body={}",
                    new String(message.getBody(), StandardCharsets.UTF_8));
            channel.basicAck(tag, false);
            return;
        }
        // payload.agentId = 执行者（assignedAgentId），0 为 null 占位 → 归一为 null
        Long rawAgentId = toLong(payload.get("agentId"));
        Long executorAgentId = (rawAgentId != null && rawAgentId == 0L) ? null : rawAgentId;
        String eventId = toText(payload.get("eventId"));
        String messageId = (eventId != null && !eventId.isBlank())
                ? eventId : LEGACY_ID_PREFIX + subTaskId;

        boolean processed = false;
        try {
            processed = tryConsume(messageId, CONSUMER_NAME,
                    () -> subTaskReviewService.reviewSubTask(subTaskId, executorAgentId));
        } catch (Exception e) {
            log.error("MQ 核验命令消费失败: messageId={}, subTaskId={}, agentId={}",
                    messageId, subTaskId, executorAgentId, e);
            processed = false;
        }

        if (processed) {
            channel.basicAck(tag, false);
            log.debug("MQ 核验命令 ACK: messageId={}, subTaskId={}", messageId, subTaskId);
        } else {
            // NACK 不重投：失败计数由父类 markFailed 写入 event_consumption_log；
            // 本条消息直接走 DLX，避免坏命令在主队列里反复弹回。
            channel.basicNack(tag, false, false);
            log.warn("MQ 核验命令 NACK (→ DLX): messageId={}, subTaskId={}", messageId, subTaskId);
        }
    }

    /** Jackson 反序列化 Map 时小整数默认 Integer，统一转 Long。 */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String toText(Object value) {
        return value != null ? value.toString() : null;
    }
}
