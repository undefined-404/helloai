package com.helloai.mq.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMQConfig {

    public static final String AGENT_TOPIC_EXCHANGE = "helloai.agent.exchange";
    public static final String DLX_EXCHANGE = "helloai.dlx.exchange";

    public static final String EXECUTION_COMMAND_QUEUE = "helloai.execution-command.queue";
    public static final String EXECUTION_COMMAND_EXCHANGE = "helloai.execution-command.exchange";

    public static final String EXECUTOR_QUEUE = "helloai.executor.queue";
    public static final String REVIEWER_QUEUE = "helloai.reviewer.queue";
    public static final String PLANNER_QUEUE = "helloai.planner.queue";
    public static final String NOTIFICATION_QUEUE = "helloai.notification.queue";
    public static final String DLX_QUEUE = "helloai.dlx.queue";

    @Bean
    public TopicExchange agentExchange() {
        return ExchangeBuilder.topicExchange(AGENT_TOPIC_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange executionCommandExchange() {
        return ExchangeBuilder.topicExchange(EXECUTION_COMMAND_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue executorQueue() {
        return QueueBuilder.durable(EXECUTOR_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_QUEUE)
                .build();
    }

    @Bean
    public Queue reviewerQueue() {
        return QueueBuilder.durable(REVIEWER_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_QUEUE)
                .build();
    }

    @Bean
    public Queue plannerQueue() {
        return QueueBuilder.durable(PLANNER_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_QUEUE)
                .build();
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Queue executionCommandQueue() {
        return QueueBuilder.durable(EXECUTION_COMMAND_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_QUEUE)
                .build();
    }

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_QUEUE)
                .build();
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue()).to(agentExchange()).with("agent.notification.*");
    }

    @Bean
    public Binding executionCommandBinding() {
        return BindingBuilder.bind(executionCommandQueue()).to(executionCommandExchange()).with("execution.command.*");
    }

    @Bean
    public Binding executorBinding() {
        return BindingBuilder.bind(executorQueue()).to(agentExchange()).with("agent.executor.*");
    }

    @Bean
    public Binding reviewerBinding() {
        return BindingBuilder.bind(reviewerQueue()).to(agentExchange()).with("agent.reviewer.*");
    }

    @Bean
    public Binding plannerBinding() {
        return BindingBuilder.bind(plannerQueue()).to(agentExchange()).with("agent.planner.*");
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange()).with(DLX_QUEUE);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("RabbitMQ publish NACK: correlationData={}, cause={}", correlationData, cause);
            }
        });
        template.setReturnsCallback(returned -> {
            log.warn("RabbitMQ message returned: exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
        });
        template.setMandatory(true);
        return template;
    }

    /**
     * 监听端消息转换器（§6.119）：用 Jackson2JsonMessageConverter 替换默认的
     * SimpleMessageConverter，封死 Java 序列化消息在监听转换层的反序列化入口。
     *
     * <p><b>背景</b>：spring-amqp 默认 SimpleMessageConverter 会对
     * content-type=application/x-java-serialized-object 的消息做 Java 反序列化（Phase 2F 之前
     * 旧发布器 convertAndSend(Map) 的遗留毒消息），被反序列化安全白名单拦截抛 SecurityException；
     * 且该异常抛在 MessagingMessageListenerAdapter 转换层（进入 @RabbitListener 方法体之前），
     * 消费端方法内的「坏消息 ACK」防御够不着；MANUAL ACK 模式下消息永远停在 unacked，
     * 每次重启重投、反复打 "Failed to convert message" WARN。
     *
     * <p><b>效果</b>：正常 JSON 消息（各发布器 Phase 2F 起均显式 JSON）照常转换（提取出的
     * payload 被丢弃，各消费端仍按 message.getBody() 自行 objectMapper 解析，行为不变）；
     * 非 JSON 消息（含遗留 Java 序列化毒消息）抛 MessageConversionException，被
     * ConditionalRejectingErrorHandler 判定 fatal → 拒投不重投 → 走 DLX 隔离。
     *
     * <p><b>为什么定义为全局 MessageConverter Bean 而不侵入 RabbitTemplate</b>：
     * Boot 自动装配的 rabbitListenerContainerFactory 会经 ObjectProvider 消费本 Bean
     * （yml 里 spring.rabbitmq.listener.simple.* 的 manual/concurrency 配置照常生效）；
     * 而本项目的 rabbitTemplate 是自定义 Bean、未设置 converter，且全部发布端均用
     * 显式 RabbitTemplate.send(Message)（Phase 2F 起），不经过 converter，发布侧行为不变。
     */
    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
