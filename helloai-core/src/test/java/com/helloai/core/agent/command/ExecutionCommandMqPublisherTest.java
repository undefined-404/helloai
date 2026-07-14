package com.helloai.core.agent.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.MqExecutionCommandProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.mq.config.RabbitMQConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 2F N6：{@link ExecutionCommandMqPublisher} 单测。
 *
 * <p>覆盖两条关键语义：</p>
 * <ol>
 *     <li>投递时机对齐 AFTER_COMMIT：事务活跃时只注册 sync，直到 {@code afterCommit()} 才真发；
 *         事务未提交 / 回滚时永远不发。</li>
 *     <li>显式 JSON 序列化：body 是 JSON 字节，消费端可用 {@link ObjectMapper#readValue} 完整还原
 *         {@link ExecutionCommandMqMessage} 字段；序列化失败抛 {@link IllegalStateException} 且不落 broker。</li>
 * </ol>
 *
 * <p>使用真实 {@link ObjectMapper}（不 mock 序列化路径），mock {@link RabbitTemplate} 以避免连 RabbitMQ；
 * 有事务分支用 {@link TransactionSynchronizationManager#initSynchronization()} 模拟事务上下文，
 * {@link org.junit.jupiter.api.AfterEach} 强制清理，避免污染其他测试。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionCommandMqPublisher (Phase 2F)")
class ExecutionCommandMqPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private final MqExecutionCommandProperties properties = buildProperties();
    private final ObjectMapper realObjectMapper = new ObjectMapper();
    private ExecutionCommand sampleCommand;

    private static MqExecutionCommandProperties buildProperties() {
        MqExecutionCommandProperties p = new MqExecutionCommandProperties();
        p.setRoutingKey("execution.command.created");
        return p;
    }

    @BeforeEach
    void setup() {
        sampleCommand = ExecutionCommand.builder()
                .recordId(1001L)
                .eventId("evt-abc")
                .subTaskId(2002L)
                .agentId(3003L)
                .trigger("assigned")
                .accessType(AgentAccessType.CLI_CLIENT)
                .build();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Nested
    @DisplayName("无事务上下文")
    class NoTransactionContext {

        @Test
        @DisplayName("无事务 → 立即发送到 broker，MessageProperties 正确")
        void publishSendsImmediatelyWhenNoTx() {
            ExecutionCommandMqPublisher publisher = new ExecutionCommandMqPublisher(
                    rabbitTemplate, properties, realObjectMapper);

            publisher.publish(sampleCommand);

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(rabbitTemplate, times(1)).send(
                    eq(RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE),
                    eq("execution.command.created"),
                    captor.capture());
            MessageProperties mp = captor.getValue().getMessageProperties();
            assertThat(mp.getMessageId()).isEqualTo("evt-abc");
            assertThat(mp.getCorrelationId()).isEqualTo("evt-abc");
            assertThat(mp.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(mp.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        }

        @Test
        @DisplayName("消息体为 JSON，消费端可用同一 ObjectMapper 还原全部字段")
        void publishBodyIsRestorableJson() throws Exception {
            ExecutionCommandMqPublisher publisher = new ExecutionCommandMqPublisher(
                    rabbitTemplate, properties, realObjectMapper);

            publisher.publish(sampleCommand);

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(rabbitTemplate).send(anyString(), anyString(), captor.capture());
            byte[] body = captor.getValue().getBody();

            ExecutionCommandMqMessage decoded =
                    realObjectMapper.readValue(body, ExecutionCommandMqMessage.class);
            assertThat(decoded.getRecordId()).isEqualTo(1001L);
            assertThat(decoded.getEventId()).isEqualTo("evt-abc");
            assertThat(decoded.getSubTaskId()).isEqualTo(2002L);
            assertThat(decoded.getAgentId()).isEqualTo(3003L);
            assertThat(decoded.getTrigger()).isEqualTo("assigned");
            assertThat(decoded.getAccessType()).isEqualTo("CLI_CLIENT");
        }
    }

    @Nested
    @DisplayName("有事务上下文（AFTER_COMMIT 语义）")
    class ActiveTransactionContext {

        @Test
        @DisplayName("有事务 → 仅注册 sync；未 commit 前 broker 零调用；afterCommit 触发才真发")
        void publishDefersUntilAfterCommit() {
            ExecutionCommandMqPublisher publisher = new ExecutionCommandMqPublisher(
                    rabbitTemplate, properties, realObjectMapper);

            TransactionSynchronizationManager.initSynchronization();
            publisher.publish(sampleCommand);

            // 尚未 commit：broker 层零调用
            verifyNoInteractions(rabbitTemplate);
            // 已注册 1 个 sync
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);

            // 手动触发 afterCommit：此时才交给 broker
            syncs.get(0).afterCommit();
            verify(rabbitTemplate, times(1)).send(
                    eq(RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE),
                    eq("execution.command.created"),
                    any(Message.class));
        }

        @Test
        @DisplayName("事务未 commit（回滚 / 清空同步）→ 永远不发送到 broker")
        void publishNotSentWhenTransactionRolledBack() {
            ExecutionCommandMqPublisher publisher = new ExecutionCommandMqPublisher(
                    rabbitTemplate, properties, realObjectMapper);

            TransactionSynchronizationManager.initSynchronization();
            publisher.publish(sampleCommand);

            // 模拟回滚：清空同步而不触发 afterCommit
            TransactionSynchronizationManager.clearSynchronization();

            verifyNoInteractions(rabbitTemplate);
        }
    }

    @Nested
    @DisplayName("异常与边界")
    class FailurePaths {

        @Test
        @DisplayName("JSON 序列化失败 → 抛 IllegalStateException，broker 零调用")
        void publishThrowsWhenSerializationFails() {
            ObjectMapper failingMapper = new ObjectMapper() {
                @Override
                public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
                    throw JsonMappingException.from((com.fasterxml.jackson.core.JsonGenerator) null,
                            "boom for eventId=" + ((ExecutionCommandMqMessage) value).getEventId());
                }
            };
            ExecutionCommandMqPublisher publisher = new ExecutionCommandMqPublisher(
                    rabbitTemplate, properties, failingMapper);

            assertThatThrownBy(() -> publisher.publish(sampleCommand))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("evt-abc")
                    .hasCauseInstanceOf(JsonProcessingException.class);
            verifyNoInteractions(rabbitTemplate);
        }
    }
}
