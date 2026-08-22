package com.helloai.core.agent.command;

import com.helloai.common.config.AgentCommandOutboxRelayProperties;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.config.MqExecutionCommandProperties;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.mq.config.RabbitMQConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Phase 2E N6 引入 / Phase 2H ②b 扩展 / 改造：执行命令派发链路启动期校验器。
 *
 * <p>职责：</p>
 * <ol>
 *     <li>把生产端 {@code dispatch-mode}、消费端 {@code consumer-mode} 与 MQ 生产/消费开关一次性打印到启动日志，
 *         便于生产环境从日志一眼看清"这台机器现在到底走哪条路"；</li>
 *     <li>对"配置组合本身矛盾"的情况 fail-fast：
 *         <ul>
 *             <li>②a：若 {@code dispatch-mode ∈ {MQ, BOTH}} 但 {@code mq.execution-command.producer-enabled=false}，
 *                 或 Publisher Bean 不可用，则抛 {@link IllegalStateException}；</li>
 *             <li>②b：若 {@code dispatch-mode ∈ {MQ, BOTH}} 但 {@code outbox.relay.enabled=false}，
 *                 同样抛 {@link IllegalStateException}；</li>
 *             <li>若 {@code consumer-mode ∈ {POLLER, BOTH}} 但 {@code mq.execution-command.consumer-enabled=false}，
 *                 直接抛 {@link IllegalStateException}，杜绝"POLLER/BOTH 主消费路径失效但 Poller 仅兜底，
 *                 命令永远停在 PENDING"的部署形态。</li>
 *         </ul>
 *     </li>
 *     <li>Bean 实际存在性由 Spring 装配阶段保证：{@code relay.enabled=true} 时
 *         {@code OutboxRelayTask} 由 {@code @ConditionalOnProperty(matchIfMissing=true)} 自动注册，
 *         反之则会被条件装配过滤——属性已足以反映 Bean 期望状态。</li>
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
    private final AgentCommandOutboxRelayProperties outboxRelayProperties;
    // 阶段五：存在性探测改 Optional 注入（无 Bean 时 empty，语义与 getIfAvailable 等价）
    private final Optional<ExecutionCommandMqPublisher> mqPublisherProvider;

    public ExecutionDispatchValidator(AgentExecutionProperties executionProperties,
                                      MqExecutionCommandProperties mqProperties,
                                      AgentCommandOutboxRelayProperties outboxRelayProperties,
                                      Optional<ExecutionCommandMqPublisher> mqPublisherProvider) {
        this.executionProperties = executionProperties;
        this.mqProperties = mqProperties;
        this.outboxRelayProperties = outboxRelayProperties;
        this.mqPublisherProvider = mqPublisherProvider;
    }

    @PostConstruct
    public void validateAndReport() {
        AgentExecutionProperties.DispatchMode dispatchMode = executionProperties.getDispatchMode();
        AgentExecutionProperties.ConsumerMode consumerMode = executionProperties.getConsumerMode();
        boolean producerEnabled = mqProperties.isProducerEnabled();
        boolean consumerEnabled = mqProperties.isConsumerEnabled();
        boolean relayEnabled = outboxRelayProperties != null && outboxRelayProperties.isEnabled();

        log.info("execution-dispatch.config dispatch-mode={} consumer-mode={} "
                        + "mq.producer-enabled={} mq.consumer-enabled={} "
                        + "outbox.relay.enabled={} "
                        + "exchange={} queue={} routing-key={}",
                dispatchMode, consumerMode,
                producerEnabled, consumerEnabled,
                relayEnabled,
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
            if (mqPublisherProvider.isEmpty()) {
                throw new IllegalStateException(
                        "helloai.execution.dispatch-mode=" + dispatchMode
                                + " 但 ExecutionCommandMqPublisher Bean 不可用（可能因 RabbitMQ 依赖缺失或条件装配失败），"
                                + "拒绝以隐式跳过 MQ 的方式启动");
            }
            // ②b：outbox 主投递必须由 Relay 周期任务推进；Relay 关闭时 outbox 行将永远停在 PENDING，
            // 因此启动期直接阻断，不允许静默"配了 MQ 没人 Relay"的部署形态。
            // 备注：本校验只读属性；Bean 实际装配由 OutboxRelayTask 的 @ConditionalOnProperty(matchIfMissing=true)
            // 在 Spring 上下文阶段保证，属性与 Bean 期望状态一致，不需要在这里再交叉验证。
            if (!relayEnabled) {
                throw new IllegalStateException(
                        "helloai.execution.dispatch-mode=" + dispatchMode
                                + " 要求同时开启 helloai.outbox.relay.enabled=true，"
                                + "当前 relay.enabled=false，会导致 agent_command_outbox 行永远停在 PENDING，"
                                + "拒绝以隐式跳过 Relay 的方式启动");
            }
            log.info("execution-dispatch.validate dispatch-mode={} producer-enabled=true publisher-bean=ready"
                            + " outbox.relay.enabled={}",
                    dispatchMode, relayEnabled);
        }

        // consumer-mode ∈ {POLLER, BOTH} 时强制要求 consumer-enabled=true。
        // POLLER/BOTH 模式下 MQ Consumer（@RabbitListener）作为主消费路径；
        // 若 consumer-enabled=false 则 MqExecutionCommandConsumer Bean 不存在，
        // 没有主消费路径仅有 Poller 兜底扫描孤儿 PENDING，命令会永远停在 PENDING。
        // 注意：当 dispatch-mode=NONE 时也会触发该校验（POLLER 模式可与 NONE 搭配，
        // 由外部 MQ Consumer 独立消费 outbox 派发的命令）。
        if ((consumerMode == AgentExecutionProperties.ConsumerMode.POLLER
                || consumerMode == AgentExecutionProperties.ConsumerMode.BOTH)
                && !consumerEnabled) {
            throw new IllegalStateException(
                    "helloai.execution.consumer-mode=" + consumerMode
                            + " 要求同时开启 helloai.mq.execution-command.consumer-enabled=true，"
                            + "当前 consumer-enabled=false，POLLER/BOTH 模式下没有主消费路径、仅有 Poller 孤儿兜底，"
                            + "agent_execution_record PENDING 行将永远不被消费，拒绝以隐式跳过 MQ 主消费的方式启动");
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
