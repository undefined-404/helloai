package com.helloai.mq.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 领域事件 MQ 发布器。
 *
 * <p><b>Phase 2F 修正（与 ExecutionCommandMqPublisher 同款）：显式 JSON 序列化。</b>
 * 原实现 rabbitTemplate.convertAndSend(POJO/Map) 依赖 SimpleMessageConverter 的 Java 序列化
 * （content-type=application/x-java-serialized-object），消费端 @RabbitListener 在转换层被
 * 反序列化安全白名单拦截（SecurityException: Attempt to deserialize unauthorized class
 * java.util.LinkedHashMap）→ Failed to convert message 无限 requeue。
 * 现改为显式 ObjectMapper.writeValueAsBytes + RabbitTemplate.send + ContentType JSON，
 * 与各消费端 objectMapper.readValue(byte[]) 解析对称，且不侵入全局 RabbitTemplate 的
 * converter，避免波及其他 RabbitListener。</p>
 */
@Slf4j
@Component
public class DomainEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public DomainEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发布领域事件到 RabbitMQ（JSON body，PERSISTENT）。
     *
     * <p>序列化失败抛 IllegalStateException，由调用方（如 Outbox 补偿任务）按业务失败处理。</p>
     *
     * @param exchange   目标交换机
     * @param routingKey 路由键
     * @param message    事件载荷（Map / POJO 均可，统一 JSON 序列化）
     */
    public void publish(String exchange, String routingKey, Object message) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(message);
        } catch (JsonProcessingException e) {
            log.error("domain-event.serialize.failed exchange={} routingKey={}",
                    exchange, routingKey, e);
            throw new IllegalStateException(
                    "领域事件序列化失败: exchange=" + exchange + ", routingKey=" + routingKey, e);
        }
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        log.info("Publishing event to exchange: {}, routingKey: {}, bodyBytes={}",
                exchange, routingKey, body.length);
        rabbitTemplate.send(exchange, routingKey, new Message(body, props));
    }
}
