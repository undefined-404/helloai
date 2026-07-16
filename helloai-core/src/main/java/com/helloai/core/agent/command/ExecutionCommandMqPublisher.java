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
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Phase 2E N6 引入 / Phase 2F 修正 / Phase 2H ②b 收尾：执行命令生产端 MQ 投递器。
 *
 * <p>与本地事件路径并列，实现"调度只发命令、执行独立消费"目标态里的 MQ 主链路生产端一环：</p>
 * <ol>
 *     <li>由 OutboxRelayTask 拉取 PENDING 行后调用 {@link #publishWithCorrelation}；
 *         业务事务与发布解耦，不再出现"事务回滚但消息已发"或"消费端读未提交行"两类问题；</li>
 *     <li>本 Bean 的注册受独立开关 helloai.mq.execution-command.producer-enabled=true 保护；</li>
 *     <li>消费端由 MqExecutionCommandConsumer 单独控制（helloai.mq.execution-command.consumer-enabled=true）；</li>
 *     <li>topology（交换机 / 队列 / binding）由 {@link RabbitMQConfig} 用常量声明，Publisher 不动 topology。</li>
 * </ol>
 *
 * <p>幂等策略：Publisher 不做去重，eventId 作为 MessageProperties.messageId 落到消息头，
 * 去重由消费端 AbstractIdempotentConsumer 的 Redis + DB 双层机制保证。</p>
 *
 * <p><b>Phase 2H ②b 收尾：AFTER_COMMIT 语义已移除。</b>
 * 2F 阶段曾用 TransactionSynchronization.afterCommit 把 publish 推迟到事务提交后。
 * ②a 引入 Outbox 后，唯一调用路径变成 OutboxRelayTask 扫 PENDING → publish，不再位于业务事务体；
 * 旧的 publish(ExecutionCommand) 入口被删除以消除第二套时序假设。
 * 调用方拿到返回的 {@link CorrelationData} 后通过 ConfirmCallback 把 broker 回执写回 Outbox 行
 * （status=CONFIRMED/FAILED + confirmed_at/last_sent_at）。</p>
 *
 * <p><b>Phase 2F 关键修正二：显式 JSON 序列化。</b>
 * 原实现 rabbitTemplate.convertAndSend(POJO) 依赖 SimpleMessageConverter，
 * 而 ExecutionCommandMqMessage 既非 Serializable 也无对应 converter → 抛 MessageConversionException，
 * 链路根本发不出去；消费端反而是按 JSON 用 objectMapper.readValue(byte[]) 解析。
 * 现改为显式 ObjectMapper.writeValueAsBytes + RabbitTemplate.send(String, String, Message)，
 * 与消费端完全对称，且不侵入全局 RabbitTemplate 的 converter，避免波及其他 RabbitListener。</p>
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
     * 把执行命令投递到 RabbitMQ，返回 CorrelationData 供 Outbox confirm/retry 异步回写。
     *
     * <p>序列化 / 网络失败由 {@link #doPublish} 抛出，交由上层承担；DB Poller 兜底路径负责补偿。</p>
     *
     * @param command        领域命令；调用方保证非空
     * @param correlationKey 用于 Publisher Confirms 回写的关联键，Outbox 场景为 eventId
     * @return CorrelationData 持有 correlationKey，供 ConfirmCallback / ReturnCallback 异步填回
     */
    public CorrelationData publishWithCorrelation(ExecutionCommand command, String correlationKey) {
        return doPublish(command, correlationKey);
    }

    private CorrelationData doPublish(ExecutionCommand command, String correlationKey) {
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
        CorrelationData correlationData = new CorrelationData(correlationKey);
        rabbitTemplate.send(
                RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE,
                properties.getRoutingKey(),
                amqp,
                correlationData);
        log.info("mq.execution-command.publish eventId={} subTaskId={} agentId={} routingKey={} bodyBytes={}",
                command.getEventId(), command.getSubTaskId(), command.getAgentId(),
                properties.getRoutingKey(), body.length);
        return correlationData;
    }
}