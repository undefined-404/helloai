package com.helloai.core.agent.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.MqExecutionCommandProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.mq.config.RabbitMQConfig;
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
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Phase 2H ②b 收尾：{@link ExecutionCommandMqPublisher} 单测。
 *
 * <p>Phase 2F 的"AFTER_COMMIT 推迟"语义在 ②a 引入 Outbox 后已无调用方，本轮删除
 * {@code publish(ExecutionCommand)} 入口并同步清掉对应测试分支。
 * 现仅覆盖 {@link ExecutionCommandMqPublisher#publishWithCorrelation}：</p>
 * <ol>
 *     <li>显式 JSON 序列化：body 是 JSON 字节，消费端可用 {@link ObjectMapper#readValue} 完整还原
 *         {@link ExecutionCommandMqMessage} 字段；</li>
 *     <li>MessageProperties 正确：contentType/encoding/messageId/correlationId/deliveryMode 全部对齐消费端；</li>
 *     <li>返回的 {@link CorrelationData#getId()} 等于 correlationKey，供 Outbox confirm 回写。</li>
 *     <li>序列化失败抛 {@link IllegalStateException} 且不落 broker。</li>
 * </ol>
 *
 * <p>使用真实 {@link ObjectMapper}（不 mock 序列化路径），mock {@link RabbitTemplate} 以避免连 RabbitMQ。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionCommandMqPublisher (Phase 2H ②b)")
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
    }

    @Nested
    @DisplayName("publishWithCorrelation：投递即落 broker")
    class DirectPublish {

        @Test
        @DisplayName("调用后立即发送 broker，MessageProperties 正确，返回 CorrelationData")
        void publishWithCorrelationSendsImmediately() {
            ExecutionCommandMqPublisher publisher = new ExecutionCommandMqPublisher(
                    rabbitTemplate, properties, realObjectMapper);

            CorrelationData returned = publisher.publishWithCorrelation(sampleCommand, "evt-abc");

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            ArgumentCaptor<CorrelationData> cdCaptor = ArgumentCaptor.forClass(CorrelationData.class);
            verify(rabbitTemplate, times(1)).send(
                    eq(RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE),
                    eq("execution.command.created"),
                    captor.capture(),
                    cdCaptor.capture());
            MessageProperties mp = captor.getValue().getMessageProperties();
            assertThat(mp.getMessageId()).isEqualTo("evt-abc");
            assertThat(mp.getCorrelationId()).isEqualTo("evt-abc");
            assertThat(mp.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(mp.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            assertThat(cdCaptor.getValue().getId()).isEqualTo("evt-abc");
            // 返回值也要携带 correlationKey，供 ConfirmCallback 异步回写
            assertThat(returned).isNotNull();
            assertThat(returned.getId()).isEqualTo("evt-abc");
        }

        @Test
        @DisplayName("消息体为 JSON，消费端可用同一 ObjectMapper 还原全部字段")
        void publishBodyIsRestorableJson() throws Exception {
            ExecutionCommandMqPublisher publisher = new ExecutionCommandMqPublisher(
                    rabbitTemplate, properties, realObjectMapper);

            publisher.publishWithCorrelation(sampleCommand, "evt-abc");

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            verify(rabbitTemplate).send(anyString(), anyString(), captor.capture(), any(CorrelationData.class));
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

        @Test
        @DisplayName("correlationKey 与 eventId 不一致时，MessageProperties 仍以 eventId 为准，返回的 CorrelationData 携带 correlationKey")
        void publishUsesCorrelationKeyOnlyOnReturnedCorrelationData() {
            ExecutionCommandMqPublisher publisher = new ExecutionCommandMqPublisher(
                    rabbitTemplate, properties, realObjectMapper);

            CorrelationData returned = publisher.publishWithCorrelation(sampleCommand, "outbox-row-42");

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            ArgumentCaptor<CorrelationData> cdCaptor = ArgumentCaptor.forClass(CorrelationData.class);
            verify(rabbitTemplate).send(
                    anyString(), anyString(), captor.capture(), cdCaptor.capture());
            // MessageProperties 始终用 eventId，便于消费端幂等
            assertThat(captor.getValue().getMessageProperties().getMessageId()).isEqualTo("evt-abc");
            // CorrelationData 携带 caller 传入的 outbox 主键，供 Confirm 回写精准定位行
            assertThat(cdCaptor.getValue().getId()).isEqualTo("outbox-row-42");
            assertThat(returned.getId()).isEqualTo("outbox-row-42");
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

            assertThatThrownBy(() -> publisher.publishWithCorrelation(sampleCommand, "evt-abc"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("evt-abc")
                    .hasCauseInstanceOf(JsonProcessingException.class);
            verifyNoInteractions(rabbitTemplate);
        }
    }
}