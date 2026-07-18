package com.helloai.job.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.mq.config.RabbitMQConfig;
import com.helloai.mq.consumer.AbstractIdempotentConsumer;
import com.helloai.mq.service.MessageDeduplicationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 通知消息消费者。
 * 消费通知队列，将 MQ 事件投递到目标 Agent 的收件箱（agent_inbox 表）。
 */
@Slf4j
@Component
public class NotificationConsumer extends AbstractIdempotentConsumer {

    private final AgentInboxService agentInboxService;

    public NotificationConsumer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                 MessageDeduplicationService deduplicationService,
                                 AgentInboxService agentInboxService) {
        super(jdbcTemplate, objectMapper, deduplicationService);
        this.agentInboxService = agentInboxService;
    }

    @SuppressWarnings("unchecked")
    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void onNotification(Map<String, Object> message) {
        String eventId = (String) message.get("eventId");
        if (eventId == null) {
            log.warn("收到无 eventId 的通知消息，跳过");
            return;
        }

        tryConsume(eventId, "NotificationConsumer", () -> {
            String eventType = (String) message.getOrDefault("eventType", "unknown");
            List<Map<String, Object>> targets = (List<Map<String, Object>>) message.get("targets");

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

                agentInboxService.send(agentId, eventId, eventType,
                        title, summary, refType, refId, priority);
            }
        });
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
