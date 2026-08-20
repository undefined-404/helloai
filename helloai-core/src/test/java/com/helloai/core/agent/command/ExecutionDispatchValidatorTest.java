package com.helloai.core.agent.command;

import com.helloai.common.config.AgentCommandOutboxRelayProperties;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.config.MqExecutionCommandProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link ExecutionDispatchValidator} 单元测试。
 *
 * <p><b>覆盖矩阵</b>：</p>
 * <ul>
 *     <li><b>②a 闭环</b>：dispatch-mode ∈ {MQ, BOTH} 但 producer-enabled=false → fail-fast；
 *         Publisher Bean 不可用 → fail-fast；</li>
 *     <li><b>②b 闭环</b>：dispatch-mode ∈ {MQ, BOTH} 但 outbox.relay.enabled=false → fail-fast
 *         （否则 outbox 行永远停在 PENDING）；</li>
 *     <li><b>新闭环</b>：consumer-mode ∈ {POLLER, BOTH} 但 consumer-enabled=false → fail-fast
 *         （POLLER/BOTH 模式下 MQ Consumer 必须存在，否则没有主消费路径、Poller 仅兜底，
 *         命令永远停在 PENDING）；</li>
 *     <li><b>WARN 不阻断</b>：dispatch-mode ∈ {MQ, BOTH} 但 consumer-enabled=false → 仅 WARN
 *         （允许"先开生产端 shadow 观察，再开消费端"的灰度节奏）；</li>
 *     <li><b>合法组合</b>：dispatch-mode=NONE + consumer-mode=POLLER + consumer-enabled=true
 *         → 不抛（POLLER 可与 NONE 搭配，外部 MQ Consumer 独立消费 outbox 派发的命令）；
 *         consumer-mode=EVENT + consumer-enabled=false → 不抛（POLLER 专属闭环，EVENT 不受限）。</li>
 * </ul>
 *
 * <p>验证策略：mock {@link AgentExecutionProperties}、{@link MqExecutionCommandProperties}、
 * {@link AgentCommandOutboxRelayProperties} 与 {@link ObjectProvider}，
 * 让 {@link ExecutionDispatchValidator} 跑纯逻辑分支；不引入 Spring 上下文。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionDispatchValidator (Phase 2H ②b + T5)")
class ExecutionDispatchValidatorTest {

    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private MqExecutionCommandProperties mqProperties;
    @Mock
    private AgentCommandOutboxRelayProperties outboxRelayProperties;
    @Mock
    private ObjectProvider<ExecutionCommandMqPublisher> mqPublisherProvider;
    @Mock
    private ExecutionCommandMqPublisher mqPublisher;

    private ExecutionDispatchValidator validator;

    @BeforeEach
    void setUp() {
        // 公共默认：NONE/EVENT 模式 + 全部开关 false，但所有 stub 都 lenient
        // 允许具体用例覆盖
        lenient().when(executionProperties.getDispatchMode())
                .thenReturn(AgentExecutionProperties.DispatchMode.NONE);
        lenient().when(executionProperties.getConsumerMode())
                .thenReturn(AgentExecutionProperties.ConsumerMode.EVENT);
        lenient().when(mqProperties.isProducerEnabled()).thenReturn(false);
        lenient().when(mqProperties.isConsumerEnabled()).thenReturn(false);
        lenient().when(mqProperties.getRoutingKey()).thenReturn("execution.command.created");
        lenient().when(outboxRelayProperties.isEnabled()).thenReturn(true);
        validator = new ExecutionDispatchValidator(
                executionProperties, mqProperties, outboxRelayProperties, mqPublisherProvider);
    }

    @Nested
    @DisplayName("②a 闭环 — dispatch-mode ∈ {MQ,BOTH} + producer-enabled=false → fail-fast")
    class DispatchModeFailFastOnProducer {

        @Test
        @DisplayName("dispatch-mode=MQ + producer-enabled=false → 抛 IllegalStateException")
        void shouldFailFastWhenDispatchMqButProducerDisabled() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.MQ);
            when(mqProperties.isProducerEnabled()).thenReturn(false);

            assertThatThrownBy(() -> validator.validateAndReport())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dispatch-mode=MQ")
                    .hasMessageContaining("producer-enabled=true")
                    .hasMessageContaining("当前 producer-enabled=false");
        }

        @Test
        @DisplayName("dispatch-mode=BOTH + producer-enabled=false → 抛 IllegalStateException")
        void shouldFailFastWhenDispatchBothButProducerDisabled() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.BOTH);
            when(mqProperties.isProducerEnabled()).thenReturn(false);

            assertThatThrownBy(() -> validator.validateAndReport())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dispatch-mode=BOTH")
                    .hasMessageContaining("producer-enabled=true");
        }

        @Test
        @DisplayName("dispatch-mode=MQ + producer-enabled=true 但 Publisher Bean 不存在 → 抛 IllegalStateException")
        void shouldFailFastWhenPublisherBeanMissing() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.MQ);
            when(mqProperties.isProducerEnabled()).thenReturn(true);
            // ObjectProvider.getIfAvailable() 默认返回 null（mock 缺省行为）

            assertThatThrownBy(() -> validator.validateAndReport())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ExecutionCommandMqPublisher Bean 不可用");
        }
    }

    @Nested
    @DisplayName("②b 闭环 — dispatch-mode ∈ {MQ,BOTH} + relay-enabled=false → fail-fast")
    class DispatchModeFailFastOnRelay {

        @Test
        @DisplayName("dispatch-mode=MQ + producer OK + Publisher OK + relay=false → 抛 IllegalStateException")
        void shouldFailFastWhenDispatchMqButRelayDisabled() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.MQ);
            when(mqProperties.isProducerEnabled()).thenReturn(true);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(mqPublisher);
            when(outboxRelayProperties.isEnabled()).thenReturn(false);

            assertThatThrownBy(() -> validator.validateAndReport())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dispatch-mode=MQ")
                    .hasMessageContaining("outbox.relay.enabled=true")
                    .hasMessageContaining("agent_command_outbox 行永远停在 PENDING");
        }

        @Test
        @DisplayName("dispatch-mode=BOTH + relay=false → 抛 IllegalStateException")
        void shouldFailFastWhenDispatchBothButRelayDisabled() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.BOTH);
            when(mqProperties.isProducerEnabled()).thenReturn(true);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(mqPublisher);
            when(outboxRelayProperties.isEnabled()).thenReturn(false);

            assertThatThrownBy(() -> validator.validateAndReport())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dispatch-mode=BOTH");
        }

        @Test
        @DisplayName("dispatch-mode=MQ + producer OK + Publisher OK + relay=true → 不抛（Happy Path）")
        void shouldNotFailFastWhenAllEnabled() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.MQ);
            when(mqProperties.isProducerEnabled()).thenReturn(true);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(mqPublisher);
            when(outboxRelayProperties.isEnabled()).thenReturn(true);
            when(mqProperties.isConsumerEnabled()).thenReturn(true);

            // 不抛即为通过
            validator.validateAndReport();
        }
    }

    @Nested
    @DisplayName("T5 新闭环 — consumer-mode ∈ {POLLER,BOTH} + consumer-enabled=false → fail-fast")
    class ConsumerModeFailFast {

        @Test
        @DisplayName("consumer-mode=POLLER + consumer-enabled=false → 抛 IllegalStateException（T5 新增）")
        void shouldFailFastWhenConsumerPollerButConsumerDisabled() {
            when(executionProperties.getConsumerMode())
                    .thenReturn(AgentExecutionProperties.ConsumerMode.POLLER);
            when(mqProperties.isConsumerEnabled()).thenReturn(false);

            assertThatThrownBy(() -> validator.validateAndReport())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("consumer-mode=POLLER")
                    .hasMessageContaining("consumer-enabled=true")
                    .hasMessageContaining("POLLER/BOTH 模式下没有主消费路径")
                    .hasMessageContaining("agent_execution_record PENDING 行将永远不被消费");
        }

        @Test
        @DisplayName("consumer-mode=BOTH + consumer-enabled=false → 抛 IllegalStateException（T5 新增）")
        void shouldFailFastWhenConsumerBothButConsumerDisabled() {
            when(executionProperties.getConsumerMode())
                    .thenReturn(AgentExecutionProperties.ConsumerMode.BOTH);
            when(mqProperties.isConsumerEnabled()).thenReturn(false);

            assertThatThrownBy(() -> validator.validateAndReport())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("consumer-mode=BOTH")
                    .hasMessageContaining("consumer-enabled=true");
        }

        @Test
        @DisplayName("consumer-mode=EVENT + consumer-enabled=false → 不抛（POLLER 专属闭环，EVENT 不受限）")
        void shouldNotFailFastWhenConsumerEventAndConsumerDisabled() {
            // 默认就是 EVENT + consumer=false：不抛
            validator.validateAndReport();
        }

        @Test
        @DisplayName("consumer-mode=POLLER + consumer-enabled=true → 不抛")
        void shouldNotFailFastWhenConsumerPollerAndConsumerEnabled() {
            when(executionProperties.getConsumerMode())
                    .thenReturn(AgentExecutionProperties.ConsumerMode.POLLER);
            when(mqProperties.isConsumerEnabled()).thenReturn(true);

            validator.validateAndReport();
        }

        @Test
        @DisplayName("consumer-mode=BOTH + consumer-enabled=true → 不抛")
        void shouldNotFailFastWhenConsumerBothAndConsumerEnabled() {
            when(executionProperties.getConsumerMode())
                    .thenReturn(AgentExecutionProperties.ConsumerMode.BOTH);
            when(mqProperties.isConsumerEnabled()).thenReturn(true);

            validator.validateAndReport();
        }
    }

    @Nested
    @DisplayName("WARN 路径 — dispatch-mode ∈ {MQ,BOTH} + consumer-enabled=false 仅 WARN 不阻断")
    class DispatchWarnOnConsumerDisabled {

        @Test
        @DisplayName("dispatch-mode=MQ + consumer-enabled=false → 不抛，仅 WARN（允许 shadow 灰度）")
        void shouldWarnWhenDispatchMqButConsumerDisabled() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.MQ);
            when(mqProperties.isProducerEnabled()).thenReturn(true);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(mqPublisher);
            when(mqProperties.isConsumerEnabled()).thenReturn(false);

            // 不抛即为通过——生产端投到 MQ 但本进程不消费，跨实例/shadow 场景允许
            validator.validateAndReport();
        }

        @Test
        @DisplayName("dispatch-mode=BOTH + consumer-enabled=false → 不抛，仅 WARN")
        void shouldWarnWhenDispatchBothButConsumerDisabled() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.BOTH);
            when(mqProperties.isProducerEnabled()).thenReturn(true);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(mqPublisher);
            when(mqProperties.isConsumerEnabled()).thenReturn(false);

            validator.validateAndReport();
        }
    }

    @Nested
    @DisplayName("合法组合与组合优先级")
    class ValidCombinationsAndPriority {

        @Test
        @DisplayName("默认部署：dispatch-mode=NONE + consumer-mode=EVENT + producer=false + consumer=false → 不抛")
        void shouldAllowDefaultNoneEventDeployment() {
            // setUp 默认就是这个组合
            validator.validateAndReport();
        }

        @Test
        @DisplayName("dispatch-mode=NONE + consumer-mode=POLLER + consumer-enabled=true → 不抛（POLLER 可与 NONE 搭配）")
        void shouldAllowDispatchNoneAndConsumerPoller() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.NONE);
            when(executionProperties.getConsumerMode())
                    .thenReturn(AgentExecutionProperties.ConsumerMode.POLLER);
            when(mqProperties.isConsumerEnabled()).thenReturn(true);

            // 不抛即为通过：NONE 表示不通过 MQ 投递，但 POLLER 模式下 MQ Consumer 仍可独立消费
            // outbox 派发的命令（如外部 MQ Producer）
            validator.validateAndReport();
        }

        @Test
        @DisplayName("dispatch-mode=NONE + consumer-mode=BOTH + consumer-enabled=true → 不抛")
        void shouldAllowDispatchNoneAndConsumerBoth() {
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.NONE);
            when(executionProperties.getConsumerMode())
                    .thenReturn(AgentExecutionProperties.ConsumerMode.BOTH);
            when(mqProperties.isConsumerEnabled()).thenReturn(true);

            validator.validateAndReport();
        }

        @Test
        @DisplayName("dispatch-mode=MQ 但 relay=false → 先抛 ②b fail-fast，不进入 consumer-mode 校验")
        void shouldPrioritizeDispatchModeValidationOverConsumerMode() {
            // 组合：dispatch-mode=MQ + relay=false + consumer-mode=POLLER + consumer-enabled=false
            // 期望：抛 dispatch-mode 相关异常，而非 consumer-mode 异常（dispatch-mode 校验在前）
            when(executionProperties.getDispatchMode())
                    .thenReturn(AgentExecutionProperties.DispatchMode.MQ);
            when(mqProperties.isProducerEnabled()).thenReturn(true);
            when(mqPublisherProvider.getIfAvailable()).thenReturn(mqPublisher);
            when(outboxRelayProperties.isEnabled()).thenReturn(false);
            when(executionProperties.getConsumerMode())
                    .thenReturn(AgentExecutionProperties.ConsumerMode.POLLER);
            when(mqProperties.isConsumerEnabled()).thenReturn(false);

            assertThatThrownBy(() -> validator.validateAndReport())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outbox.relay.enabled=true")
                    // 不应包含 consumer-mode 关键词，因为 dispatch-mode 校验先抛
                    .satisfies(t -> assertThat(t.getMessage()).doesNotContain("consumer-mode=POLLER"));
        }
    }
}