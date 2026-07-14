package com.helloai.core.agent.command;

import com.helloai.common.config.MqExecutionCommandProperties;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.mq.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 2E N6：执行命令生产端 MQ 投递器。
 *
 * <p>与 {@link ApplicationEventPublisher 本地事件路径} 并列，实现"调度只发命令、执行独立消费"目标态里
 * 的<em>MQ 主链路生产端</em>一环：</p>
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
 * <p>失败可见性：{@link RabbitTemplate#setMandatory(boolean) mandatory=true} 与 confirm callback
 * 已在 {@link RabbitMQConfig#rabbitTemplate} 里配好，投递失败会在日志层面暴露；Publisher 自身仅关心"是否成功交给 broker"，
 * 不做重试（重试与补偿由 DB Poller 兜底路径承担）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "helloai.mq.execution-command.producer-enabled", havingValue = "true")
public class ExecutionCommandMqPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final MqExecutionCommandProperties properties;

    public ExecutionCommandMqPublisher(RabbitTemplate rabbitTemplate,
                                       MqExecutionCommandProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        log.info("execution-command.mq-publisher.init exchange={} routingKey={}",
                RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE, properties.getRoutingKey());
    }

    /**
     * 把执行命令投递到 RabbitMQ。
     *
     * <p>使用 {@code RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE} 常量与 properties 中的 routingKey，
     * 保证 topology 由 {@link RabbitMQConfig} 唯一声明；后处理器把 {@code eventId} 塞进
     * {@code messageId} 与 {@code correlationId}，同时把投递模式设为 PERSISTENT，避免 broker 重启丢消息。</p>
     *
     * @param command 领域命令；调用方保证非空
     */
    public void publish(ExecutionCommand command) {
        ExecutionCommandMqMessage message = ExecutionCommandMqMessage.from(command);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE,
                properties.getRoutingKey(),
                message,
                mp -> {
                    mp.getMessageProperties().setMessageId(command.getEventId());
                    mp.getMessageProperties().setCorrelationId(command.getEventId());
                    mp.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return mp;
                });
        log.info("mq.execution-command.publish eventId={} subTaskId={} agentId={} routingKey={}",
                command.getEventId(), command.getSubTaskId(), command.getAgentId(), properties.getRoutingKey());
    }
}
