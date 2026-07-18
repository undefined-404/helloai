package com.helloai.job.task;

import com.helloai.common.constant.OutboxStatus;
import com.helloai.core.agent.entity.AgentOutboxEvent;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.mq.config.RabbitMQConfig;
import com.helloai.mq.producer.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentEventCompensationTask {

    private final AgentOutboxService agentOutboxService;
    private final DomainEventPublisher eventPublisher;
    private final StringRedisTemplate redis;

    private static final String LOCK_KEY = "scheduler:lock:AgentOutbox";

    @Scheduled(fixedRate = 15000)
    public void compensate() {
        if (!tryLock()) return;

        try {
            List<AgentOutboxEvent> pending = agentOutboxService.pollPending(100);

            for (AgentOutboxEvent event : pending) {
                try {
                    eventPublisher.publish(
                            RabbitMQConfig.AGENT_TOPIC_EXCHANGE,
                            event.getRoutingKey(),
                            event.getPayload());

                    agentOutboxService.markSuccess(event.getId());
                    log.info("Outbox compensation success: eventId={}, type={}",
                            event.getEventId(), event.getEventType());
                } catch (Exception e) {
                    log.error("Outbox compensation failed: eventId={}", event.getEventId(), e);
                    agentOutboxService.markFailed(event.getId(),
                            e.getMessage() != null ? e.getMessage().substring(0, Math.min(500, e.getMessage().length())) : "unknown");
                }
            }

            if (!pending.isEmpty()) {
                log.info("Outbox compensation processed: {} events", pending.size());
            }

        } finally {
            unlock();
        }
    }

    private boolean tryLock() {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", 30, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock() {
        redis.delete(LOCK_KEY);
    }
}
