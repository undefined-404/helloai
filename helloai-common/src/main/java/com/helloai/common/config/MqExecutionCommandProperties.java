package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 2D N6 / Phase 2E：MQ 执行命令主链路配置。
 *
 * <p>Phase 2E 起，生产端与消费端各自独立开关，支持独立灰度：</p>
 * <ul>
 *     <li>{@link #producerEnabled}：控制 {@code ExecutionCommandMqPublisher} 是否作为 Spring Bean 注册；</li>
 *     <li>{@link #consumerEnabled}：控制 {@code MqExecutionCommandConsumer} 是否作为 Spring Bean 注册。</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>
 * helloai:
 *   mq:
 *     execution-command:
 *       producer-enabled: false      # 生产端投递 MQ（配合 helloai.execution.dispatch-mode=MQ/BOTH）
 *       consumer-enabled: false      # MQ 消费骨架
 *       exchange: helloai.execution-command.exchange
 *       queue: helloai.execution-command.queue
 *       routing-key: execution.command.created
 * </pre>
 *
 * <p>注意：{@link #exchange} / {@link #queue} / {@link #routingKey} 目前作为启动期日志与调试参考，
 * 真正的 topology 由 {@code com.helloai.mq.config.RabbitMQConfig} 用常量声明，两者字符串保持一致。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.mq.execution-command")
public class MqExecutionCommandProperties {

    /**
     * 生产端开关。
     *
     * <p>true 时 {@code ExecutionCommandMqPublisher} 才会作为 Spring Bean 注册；
     * 若 {@code helloai.execution.dispatch-mode ∈ {MQ, BOTH}} 但本开关为 false，
     * 启动期 {@code ExecutionDispatchValidator} 将 fail-fast，防止隐式跳过 MQ 投递。</p>
     */
    private boolean producerEnabled = false;

    /**
     * 消费端开关。
     *
     * <p>true 时 {@code MqExecutionCommandConsumer} 才会作为 Spring Bean 注册。
     * 生产端与消费端相互独立，允许"先开生产端 shadow 观察队列堆积，再开消费端"的灰度节奏。</p>
     */
    private boolean consumerEnabled = false;

    /**
     * MQ 交换机名称（Topic 类型）。仅作为启动日志与调试参考。
     */
    private String exchange = "helloai.execution-command.exchange";

    /**
     * 消费队列名称。仅作为启动日志与调试参考。
     */
    private String queue = "helloai.execution-command.queue";

    /**
     * 路由键。与 {@code RabbitMQConfig.executionCommandBinding()} 的 {@code execution.command.*} 通配符匹配。
     */
    private String routingKey = "execution.command.created";
}
