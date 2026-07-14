package com.helloai.core.agent.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.MqExecutionCommandProperties;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.mq.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;

/**
 * Phase 2E N6 引入 / Phase 2F 修正：执行命令生产端 MQ 投递器。
 *
 * <p>与 {@link org.springframework.context.ApplicationEventPublisher 本地事件路径} 并列，
 * 实现"调度只发命令、执行独立消费"目标态里的<em>MQ 主链路生产端</em>一环：</p>
 * <ol>
 *     <li>由 {@link ExecutionCommandService} 依据 {@code helloai.execution.dispatch-mode} 决定调用；</li>
 *     <li>本 Bean 的注册受独立开关 {@code helloai.mq.execution-command.producer-enabled=true} 保护；</li>
 *     <li>消费端由 {@code MqExecutionCommandConsumer} 单独控制
 *         ({@code helloai.mq.execution-command.consumer-enabled=true})；</li>
 *     <li>topology（交换机 / 队列 / binding）由 {@link RabbitMQConfig} 用常量声明，Publisher 不动 topology。</li>
 * </ol>
 *
 * <p>幂等策略：Publisher 不做去重，{@link ExecutionCommand#getEventId()} 作为
 * {@code MessageProperties.messageId} 落到消息头，去重由消费端
 * {@link com.helloai.mq.consumer.AbstractIdempotentConsumer} 的 Redis + DB 双层机制保证。</p>
 *
 * <p><b>Phase 2F 关键修正一：投递时机对齐 AFTER_COMMIT。</b>
 * 原实现把 {@link #publish(ExecutionCommand)} 放进 {@link org.springframework.transaction.annotation.Transactional}
 * 方法体里直接调用，与本地事件 {@link org.springframework.transaction.event.TransactionalEventListener}
 * (AFTER_COMMIT) 语义不对称，会导致：<br>
 * (a) 事务回滚后消息已经发出；(b) 消费端读到"还未提交"的 subTask/agent/record 而走 ACK 丢弃分支。<br>
 * 现在：若调用发生在 Spring 事务上下文中，Publisher 只<em>注册</em>一个
 * {@link TransactionSynchronization#afterCommit()} 回调，等事务真正提交后再交给 broker；
 * 无事务上下文（脚本 / 单测）退化为立即发送。</p>
 *
 * <p><b>Phase 2F 关键修正二：显式 JSON 序列化。</b>
 * 原实现 {@code rabbitTemplate.convertAndSend(POJO)} 依赖 SimpleMessageConverter，
 * 而 {@link ExecutionCommandMqMessage} 既非 {@code Serializable} 也无对应 converter → 抛
 * {@code MessageConversionException}，链路根本发不出去；消费端反而是按 JSON 用
 * {@code objectMapper.readValue(byte[])} 解析。现改为显式 {@link ObjectMapper#writeValueAsBytes} +
 * {@link RabbitTemplate#send(String, String, Message)}，与消费端完全对称，且不侵入全局
 * {@link RabbitTemplate} 的 converter，避免波及其他 {@code @RabbitListener}。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "helloai.mq.execution-command.producer-enabled", havingValue = "true")
public class ExecutionCommandMqPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MqExecutionCommandProperties properties;
    private final ObjectMapper objectMapper;

    public ExecutionCommandMqPublisher(RabbitTemplate rabbitTemplate,
                                       MqExecutionCommandProperties properties,
                                       ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        log.info("execution-command.mq-publisher.init exchange={} routingKey={}",
                RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE, properties.getRoutingKey());
    }

    /**
     * 把执行命令投递到 RabbitMQ。
     *
     * <p>事务活跃时只注册 AFTER_COMMIT 回调；无事务时立即发送。序列化 / 网络失败由
     * {@link #doPublish} 抛出，交由上层承担；DB Poller 兜底路径负责补偿。</p>
     *
     * @param command 领域命令；调用方保证非空
     */
    public void publish(ExecutionCommand command) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublish(command);
                }
            });
            log.debug("mq.execution-command.publish.register-after-commit eventId={} subTaskId={} agentId={}",
                    command.getEventId(), command.getSubTaskId(), command.getAgentId());
        } else {
            doPublish(command);
        }
    }

    private void doPublish(ExecutionCommand command) {
        ExecutionCommandMqMessage message = ExecutionCommandMqMessage.from(command);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(message);
        } catch (JsonProcessingException e) {
            log.error("mq.execution-command.serialize.failed eventId={} subTaskId={} agentId={}",
                    command.getEventId(), command.getSubTaskId(), command.getAgentId(), e);
            throw new IllegalStateException(
                    "execution-command 序列化失败: eventId=" + command.getEventId(), e);
        }
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setMessageId(command.getEventId());
        props.setCorrelationId(command.getEventId());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message amqp = new Message(body, props);
        rabbitTemplate.send(
                RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE,
                properties.getRoutingKey(),
                amqp);
        log.info("mq.execution-command.publish eventId={} subTaskId={} agentId={} routingKey={} bodyBytes={}",
                command.getEventId(), command.getSubTaskId(), command.getAgentId(),
                properties.getRoutingKey(), body.length);
    }
}
