package com.helloai.mq.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RabbitMQConfig {

    public static final String AGENT_TOPIC_EXCHANGE = "helloai.agent.exchange";
    public static final String DLX_EXCHANGE = "helloai.dlx.exchange";

    public static final String EXECUTOR_QUEUE = "helloai.executor.queue";
    public static final String REVIEWER_QUEUE = "helloai.reviewer.queue";
    public static final String PLANNER_QUEUE = "helloai.planner.queue";
    public static final String PATROL_QUEUE = "helloai.patrol.queue";
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
    public Queue patrolQueue() {
        return QueueBuilder.durable(PATROL_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_QUEUE)
                .build();
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
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
    public Binding patrolBinding() {
        return BindingBuilder.bind(patrolQueue()).to(agentExchange()).with("agent.patrol.*");
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
}
