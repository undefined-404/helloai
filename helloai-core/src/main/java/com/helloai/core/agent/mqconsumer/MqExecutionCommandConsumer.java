package com.helloai.core.agent.mqconsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.MqExecutionCommandProperties;
import com.helloai.core.agent.domain.ExecutionCommand;
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

/**
 * Phase 2D N6：MQ 维度的执行命令消费者骨架。
 *
 * <p>本类与 {@link LocalExecutionCommandConsumer} 共同实现
 * {@link ExecutionCommandConsumer} 扩展点，遵循"调度只发命令、执行独立消费、结果异步回写"的统一哲学：</p>
 * <ul>
 *     <li>{@link LocalExecutionCommandConsumer}：本地事务事件 + DB Poller 主路径</li>
 *     <li>{@code MqExecutionCommandConsumer}：RabbitMQ 主路径（当前为骨架，默认 CONDITIONAL 关闭）</li>
 * </ul>
 *
 * <p>{@link #consume(ExecutionCommand)} 是真正的执行入口——直接委托给 {@link LocalExecutionCommandConsumer}，
 * 这样无论消息来源是本地事件、DB Poller 还是 MQ，最终执行链都收敛在同一套 6 步流程上
 * （startIfNeeded / markRunning / 消费 timeline / executeOnce / handleReport / markSuccess/markFailed）。</p>
 *
 * <p>消费骨架约束：</p>
 * <ol>
 *     <li>仅在 {@code helloai.mq.execution-command.consumer-enabled=true} 时生效，关闭后整个 Bean 不存在，不影响既有 POLLER / EVENT 主链路</li>
 *     <li>消息体为 {@link ExecutionCommandMqMessage} JSON；解析失败按 ACK 处理（写入死信也无意义）</li>
 *     <li>采用 MANUAL ACK：消费成功 → basicAck；消费失败 → basicNack(requeue=false) 走 DLX</li>
 *     <li>幂等由父类 {@link AbstractIdempotentConsumer#tryConsume} 提供 Redis + DB 双层去重</li>
 *     <li>执行链与现有主路径完全一致：本地消费链自身已带 CAS 防覆盖，MQ 路径不需要额外防重逻辑</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "helloai.mq.execution-command.consumer-enabled", havingValue = "true")
public class MqExecutionCommandConsumer extends AbstractIdempotentConsumer implements ExecutionCommandConsumer {

    /**
     * 消费者名称（用于幂等日志与 event_consumption_log 的 consumer 字段）。
     */
    static final String CONSUMER_NAME = "MqExecutionCommandConsumer";

    private final LocalExecutionCommandConsumer localDelegate;
    private final MqExecutionCommandProperties mqProperties;

    public MqExecutionCommandConsumer(JdbcTemplate jdbcTemplate,
                                      ObjectMapper objectMapper,
                                      MessageDeduplicationService deduplicationService,
                                      LocalExecutionCommandConsumer localDelegate,
                                      MqExecutionCommandProperties mqProperties) {
        super(jdbcTemplate, objectMapper, deduplicationService);
        this.localDelegate = localDelegate;
        this.mqProperties = mqProperties;
    }

    /**
     * {@link ExecutionCommandConsumer} 接口实现：MQ 路径不重复实现 6 步执行链，
     * 直接委托给 {@link LocalExecutionCommandConsumer}，保留调度分离。
     *
     * <p>通过实现同一接口，本 Bean 可以被以 {@code ExecutionCommandConsumer} 类型注入，
     * 与 {@link LocalExecutionCommandConsumer} 形成"共用 ExecutionCommandConsumer 接口"的双实现。</p>
     */
    @Override
    public void consume(ExecutionCommand command) {
        localDelegate.consume(command);
    }

    /**
     * RabbitMQ 入口：解析 → 幂等 → 委托 → ACK / NACK。
     *
     * <p>解析失败或缺 eventId 时直接 ACK（避免坏消息无限重投阻塞队列）。</p>
     */
    @RabbitListener(queues = RabbitMQConfig.EXECUTION_COMMAND_QUEUE, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        ExecutionCommandMqMessage mqMessage;
        try {
            mqMessage = objectMapper.readValue(message.getBody(), ExecutionCommandMqMessage.class);
        } catch (Exception e) {
            log.warn("MQ 执行命令消息解析失败，跳过(ACK): body={}, error={}",
                    new String(message.getBody(), StandardCharsets.UTF_8), e.getMessage());
            channel.basicAck(tag, false);
            return;
        }
        if (mqMessage == null || mqMessage.getEventId() == null || mqMessage.getEventId().isBlank()) {
            log.warn("MQ 执行命令缺少 eventId，跳过(ACK): body={}",
                    new String(message.getBody(), StandardCharsets.UTF_8));
            channel.basicAck(tag, false);
            return;
        }

        ExecutionCommand command = mqMessage.toDomain();
        boolean processed = false;
        try {
            processed = tryConsume(mqMessage.getEventId(), CONSUMER_NAME, () -> consume(command));
        } catch (Exception e) {
            log.error("MQ 执行命令消费失败: eventId={}, subTaskId={}, agentId={}",
                    mqMessage.getEventId(), mqMessage.getSubTaskId(), mqMessage.getAgentId(), e);
            processed = false;
        }

        if (processed) {
            channel.basicAck(tag, false);
            log.debug("MQ 执行命令 ACK: eventId={}", mqMessage.getEventId());
        } else {
            // NACK 不重投：失败计数由父类 markFailed 写入 event_consumption_log;
            // 本条消息直接走 DLX，避免坏命令在主队列里反复弹回。
            channel.basicNack(tag, false, false);
            log.warn("MQ 执行命令 NACK (→ DLX): eventId={}", mqMessage.getEventId());
        }
    }

    /**
     * 提供给外部（生产端 / 启动日志）的对外配置引用。
     *
     * <p>Phase 2E 起已真正注入 {@link MqExecutionCommandProperties}，不再返回 null。</p>
     */
    public MqExecutionCommandProperties describeProperties() {
        return mqProperties;
    }
}
