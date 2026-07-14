package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Phase 2D N6：MQ 执行命令消费骨架配置。
 *
 * <p>本类仅控制"是否启用 MQ ExecutionCommand Consumer 骨架"以及队列/交换机命名。
 * 真正的发送端（{@code ExecutionCommandMqPublisher}）与消费端
 * （{@code MqExecutionCommandConsumer}）均以 {@link #enabled} 作为 {@code @ConditionalOnProperty}
 * 总开关，避免在没有 RabbitMQ 的开发/测试环境启动失败。</p>
 *
 * <p>典型用法（仅在生产或具备 RabbitMQ 的回归环境启用）：</p>
 * <pre>
 * helloai:
 *   mq:
 *     execution-command:
 *       enabled: true                # 启用 MQ 消费骨架（默认 false，骨架阶段）
 *       exchange: helloai.execution-command.exchange
 *       queue: helloai.execution-command.queue
 *       routing-key: execution.command.created
 * </pre>
 *
 * <p>注意：本轮仅引入消费骨架，不修改 ExecutionCommandService 的发布逻辑。
 * MQ 主链路主线化（把"发布 MQ"作为 POLLER/EVENT 之外的第三种主消费路径）将在后续轮次推进。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.mq.execution-command")
public class MqExecutionCommandProperties {

    /**
     * 总开关。
     *
     * <p>false 时 MqExecutionCommandConsumer / MqExecutionCommandPublisher 均为 no-op Bean，
     * 既有 {@code consumer-mode=POLLER / EVENT / BOTH} 主链路不受影响。</p>
     */
    private boolean enabled = false;

    /**
     * MQ 交换机名称（Topic 类型）。
     */
    private String exchange = "helloai.execution-command.exchange";

    /**
     * 消费队列名称。
     */
    private String queue = "helloai.execution-command.queue";

    /**
     * 路由键。
     */
    private String routingKey = "execution.command.created";
}
