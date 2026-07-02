package com.helloai.mq.producer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public DomainEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String exchange, String routingKey, Object message) {
        log.info("Publishing event to exchange: {}, routingKey: {}", exchange, routingKey);
        rabbitTemplate.convertAndSend(exchange, routingKey, message);
    }
}
