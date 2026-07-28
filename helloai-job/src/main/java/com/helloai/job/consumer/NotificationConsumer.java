package com.helloai.job.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.task.service.ReviewService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskService;
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
