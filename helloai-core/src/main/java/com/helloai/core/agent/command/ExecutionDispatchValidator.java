package com.helloai.core.agent.command;

import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.config.MqExecutionCommandProperties;
import com.helloai.mq.config.RabbitMQConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Phase 2E N6：执行命令派发链路启动期校验器。
 *
 * <p>职责：</p>
 * <ol>
 *     <li>把生产端 {@code dispatch-mode}、消费端 {@code consumer-mode} 与 MQ 生产/消费开关一次性打印到启动日志，
 *         便于生产环境从日志一眼看清"这台机器现在到底走哪条路"；</li>
 *     <li>对"配置组合本身矛盾"的情况 fail-fast：
 *         若 {@code dispatch-mode ∈ {MQ, BOTH}} 但 {@code mq.execution-command.producer-enabled=false}，
 *         或 Publisher Bean 不可用（例如 producer-enabled 打开但 Bean 因其他原因未注册），
 *         则抛 {@link IllegalStateException} 直接让上下文启动失败，避免生产上出现"配了 MQ 却隐式没发"的静默 bug。</li>
 * </ol>
 *
 * <p>本类<em>只做</em>校验与打印，不承担任何运行时分发逻辑。运行时分发在
 * {@link ExecutionCommandService#createAssignedCommand} 内完成，二者相互独立。</p>
 */
@Slf4j
@Component
public class ExecutionDispatchValidator {

    private final AgentExecutionProperties executionProperties;
    private final MqExecutionCommandProperties mqProperties;
    private final ObjectProvider<ExecutionCommandMqPublisher> mqPublisherProvider;

    public ExecutionDispatchValidator(AgentExecutionProperties executionProperties,
                                      MqExecutionCommandProperties mqProperties,
                                      ObjectProvider<ExecutionCommandMqPublisher> mqPublisherProvider) {
        this.executionProperties = executionProperties;
        this.mqProperties = mqProperties;
        this.mqPublisherProvider = mqPublisherProvider;
    }

    @PostConstruct
    public void validateAndReport() {
        AgentExecutionProperties.DispatchMode dispatchMode = executionProperties.getDispatchMode();
        AgentExecutionProperties.ConsumerMode consumerMode = executionProperties.getConsumerMode();
        boolean producerEnabled = mqProperties.isProducerEnabled();
        boolean consumerEnabled = mqProperties.isConsumerEnabled();

        log.info("execution-dispatch.config dispatch-mode={} consumer-mode={} "
                        + "mq.producer-enabled={} mq.consumer-enabled={} "
                        + "exchange={} queue={} routing-key={}",
                dispatchMode, consumerMode,
                producerEnabled, consumerEnabled,
                RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE,
                RabbitMQConfig.EXECUTION_COMMAND_QUEUE,
                mqProperties.getRoutingKey());

        if (dispatchMode == AgentExecutionProperties.DispatchMode.MQ
                || dispatchMode == AgentExecutionProperties.DispatchMode.BOTH) {
            if (!producerEnabled) {
                throw new IllegalStateException(
                        "helloai.execution.dispatch-mode=" + dispatchMode
                                + " 要求同时开启 helloai.mq.execution-command.producer-enabled=true，"
                                + "当前 producer-enabled=false，拒绝以隐式跳过 MQ 的方式启动");
            }
            if (mqPublisherProvider.getIfAvailable() == null) {
                throw new IllegalStateException(
                        "helloai.execution.dispatch-mode=" + dispatchMode
                                + " 但 ExecutionCommandMqPublisher Bean 不可用（可能因 RabbitMQ 依赖缺失或条件装配失败），"
                                + "拒绝以隐式跳过 MQ 的方式启动");
            }
            log.info("execution-dispatch.validate dispatch-mode={} producer-enabled=true publisher-bean=ready", dispatchMode);
        }

        // BOTH 灰度或 MQ 主链投产阶段，consumer-enabled 通常应同步打开，但允许生产端 shadow 观察队列堆积后再开消费端，因此只 WARN 不阻断
        if ((dispatchMode == AgentExecutionProperties.DispatchMode.MQ
                || dispatchMode == AgentExecutionProperties.DispatchMode.BOTH)
                && !consumerEnabled) {
            log.warn("execution-dispatch.warn dispatch-mode={} 但 helloai.mq.execution-command.consumer-enabled=false，"
                            + "命令将投递到 MQ 但不会被本进程消费（shadow / 跨实例消费场景请忽略此告警）",
                    dispatchMode);
        }
    }
}
