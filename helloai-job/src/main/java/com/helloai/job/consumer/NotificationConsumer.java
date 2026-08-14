package com.helloai.job.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.task.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
import com.helloai.mq.config.RabbitMQConfig;
import com.helloai.mq.consumer.AbstractIdempotentConsumer;
import com.helloai.mq.service.MessageDeduplicationService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 通知消息消费者。
 * 消费通知队列，将 MQ 事件投递到目标 Agent 的收件箱（agent_inbox 表）。
 * 消息体为生产端显式 JSON 序列化（Phase 2F 修正），此处按 JSON 解析。
 */
@Slf4j
@Component
public class NotificationConsumer extends AbstractIdempotentConsumer {

    private final AgentInboxService agentInboxService;
    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final ReviewService reviewService;

    public NotificationConsumer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                 MessageDeduplicationService deduplicationService,
                                 AgentInboxService agentInboxService,
                                 TaskService taskService,
                                 SubTaskService subTaskService,
                                 ReviewService reviewService) {
        super(jdbcTemplate, objectMapper, deduplicationService);
        this.agentInboxService = agentInboxService;
        this.taskService = taskService;
        this.subTaskService = subTaskService;
        this.reviewService = reviewService;
    }

    /**
     * RabbitMQ 入口：JSON 解析 body → 幂等 → 投递 Agent 收件箱。
     *
     * <p>解析失败或缺 eventId 时直接 ACK（坏消息不阻塞队列）；消费失败 NACK 不重投，
     * 由父类 markFailed 记录失败计数后走 DLX，与 MqReviewCommandConsumer 同款。</p>
     */
    @SuppressWarnings("unchecked")
    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE, ackMode = "MANUAL")
    public void onNotification(Message message, Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(message.getBody(), Map.class);
        } catch (Exception e) {
            log.warn("通知消息解析失败，跳过: body={}, error={}",
                    new String(message.getBody(), StandardCharsets.UTF_8), e.getMessage());
            channel.basicAck(tag, false);
            return;
        }
        String eventId = (String) payload.get("eventId");
        if (eventId == null) {
            log.warn("收到无 eventId 的通知消息，跳过");
            channel.basicAck(tag, false);
            return;
        }

        boolean processed;
        try {
            processed = tryConsume(eventId, "NotificationConsumer", () -> {
                String eventType = (String) payload.getOrDefault("eventType", "unknown");
                List<Map<String, Object>> targets = (List<Map<String, Object>>) payload.get("targets");

                if (targets == null || targets.isEmpty()) {
                    log.debug("通知消息无目标 Agent: eventId={}", eventId);
                    return;
                }

                for (Map<String, Object> target : targets) {
                    Long agentId = toLong(target.get("agentId"));
                    if (agentId == null || agentId == 0L) continue;

                    String title = (String) target.getOrDefault("title", eventType);
                    String summary = (String) target.getOrDefault("summary", "");
                    String refType = (String) target.getOrDefault("refType", "");
                    Long refId = toLong(target.get("refId"));
                    String priority = (String) target.getOrDefault("priority", "NORMAL");

                    // 防御：目标已被级联删除时丢弃在途通知，避免写入孤儿消息
                    // 误导 Agent 拉取不存在的任务（DB 是唯一事实源，消息只是门铃）
                    if (!refTargetExists(refType, refId)) {
                        log.info("通知目标已删除，丢弃: eventId={}, refType={}, refId={}", eventId, refType, refId);
                        continue;
                    }

                    agentInboxService.send(agentId, eventId, eventType,
                            title, summary, refType, refId, priority);
                }
            });
        } catch (Exception e) {
            log.error("通知消息消费失败: eventId={}", eventId, e);
            processed = false;
        }

        if (processed) {
            channel.basicAck(tag, false);
            log.debug("通知消息 ACK: eventId={}", eventId);
        } else {
            // NACK 不重投：失败计数由父类 markFailed 写入 event_consumption_log；
            // 本条消息直接走 DLX，避免坏通知在主队列里反复弹回。
            channel.basicNack(tag, false, false);
            log.warn("通知消息 NACK (→ DLX): eventId={}", eventId);
        }
    }

    /** 校验通知引用的业务对象是否仍存在（任务级联删除后的在途消息兜底）。 */
    private boolean refTargetExists(String refType, Long refId) {
        if (refType == null || refType.isBlank() || refId == null) {
            return true;
        }
        return switch (refType) {
            case "task" -> taskService.getById(refId) != null;
            case "sub_task" -> subTaskService.getById(refId) != null;
            case "review" -> reviewService.getById(refId) != null;
            default -> true;
        };
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
