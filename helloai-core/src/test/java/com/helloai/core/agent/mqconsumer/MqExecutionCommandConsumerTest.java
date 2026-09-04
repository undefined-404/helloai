package com.helloai.core.agent.mqconsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.MqExecutionCommandProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.mq.service.MessageDeduplicationService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 2D N6：{@link MqExecutionCommandConsumer} 骨架单元测试。
 *
 * <p>覆盖 6 类行为：</p>
 * <ol>
 *     <li>正常消息：解析 → consume → ACK</li>
 *     <li>消息体无法解析：ACK（坏消息不阻塞队列）+ 不调用 consume</li>
 *     <li>缺 eventId：ACK + 不调用 consume</li>
 *     <li>委托抛异常：NACK(requeue=false) → DLX</li>
 *     <li>幂等命中：直接 ACK，不重复调用 consume</li>
 *     <li>{@link MqExecutionCommandConsumer#consume(ExecutionCommand)} 自身委托给 {@link LocalExecutionCommandConsumer}</li>
 * </ol>
 *
 * <p>本测试不启动 Spring 容器与 RabbitMQ，全部基于 Mockito + 真实 Jackson 序列化。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MqExecutionCommandConsumer")
class MqExecutionCommandConsumerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private MessageDeduplicationService deduplicationService;

    @Mock
    private LocalExecutionCommandConsumer localDelegate;

    @Mock
    private Message amqpMessage;

    @Mock
    private Channel channel;

    private MqExecutionCommandConsumer consumer;

    @BeforeEach
    void setUp() {
        MqExecutionCommandProperties properties = new MqExecutionCommandProperties();
        consumer = new MqExecutionCommandConsumer(jdbcTemplate, MAPPER, deduplicationService, localDelegate, properties);
    }

    /**
     * 真实消息体构造器。
     */
    /** 消息体含 requiredSkills（Phase 1 Step 1 fix：装箱字段经 MQ 反序列化不丢）。 */
    private byte[] buildMessageBody(String eventId, Long subTaskId, Long agentId, String accessType) {
        ExecutionCommandMqMessage msg = ExecutionCommandMqMessage.builder()
                .recordId(1001L)
                .eventId(eventId)
                .subTaskId(subTaskId)
                .agentId(agentId)
                .trigger("assigned")
                .accessType(accessType)
                .requiredSkills(List.of("eng-code-review"))
                .build();
        try {
            return MAPPER.writeValueAsBytes(msg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("正常消息流")
    class HappyPath {

        @Test
        @DisplayName("解析成功 → 委托 consume → ACK")
        void shouldDelegateAndAckOnValidMessage() throws Exception {
            byte[] body = buildMessageBody("evt-1", 11L, 22L, "API_KEY_LLM");
            when(amqpMessage.getBody()).thenReturn(body);
            when(deduplicationService.isDuplicate("evt-1")).thenReturn(false);

            consumer.onMessage(amqpMessage, channel, 99L);

            // Phase 1 Step 1 fix：requiredSkills 装箱字段经 MQ 反序列化后不丢（toDomain 透传）
            ArgumentCaptor<ExecutionCommand> cmdCaptor = ArgumentCaptor.forClass(ExecutionCommand.class);
            verify(localDelegate).consume(cmdCaptor.capture());
            assertThat(cmdCaptor.getValue().getRequiredSkills()).isEqualTo(List.of("eng-code-review"));
            verify(deduplicationService).markConsumed("evt-1", MqExecutionCommandConsumer.CONSUMER_NAME);
            verify(channel).basicAck(eq(99L), eq(false));
            verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
        }

        @Test
        @DisplayName("consume(command) 显式委托给 LocalExecutionCommandConsumer")
        void shouldForwardConsumeToLocalDelegate() {
            ExecutionCommand command = ExecutionCommand.builder()
                    .eventId("evt-2")
                    .subTaskId(11L)
                    .agentId(22L)
                    .accessType(AgentAccessType.API_KEY_LLM)
                    .trigger("assigned")
                    .build();

            consumer.consume(command);

            verify(localDelegate).consume(same(command));
            verify(localDelegate, never()).consume(null);
        }
    }

    @Nested
    @DisplayName("异常与边界")
    class EdgeCases {

        @Test
        @DisplayName("消息体非法 JSON → ACK + 不调用 consume")
        void shouldAckWhenBodyIsInvalidJson() throws Exception {
            byte[] garbage = "this is not json".getBytes(StandardCharsets.UTF_8);
            when(amqpMessage.getBody()).thenReturn(garbage);

            consumer.onMessage(amqpMessage, channel, 100L);

            verify(localDelegate, never()).consume(any());
            verify(channel).basicAck(eq(100L), eq(false));
            verify(channel, never()).basicNack(anyLong(), any(Boolean.class), any(Boolean.class));
        }

        @Test
        @DisplayName("缺 eventId → ACK + 不调用 consume")
        void shouldAckWhenEventIdMissing() throws Exception {
            byte[] body = buildMessageBody(null, 11L, 22L, "API_KEY_LLM");
            when(amqpMessage.getBody()).thenReturn(body);

            consumer.onMessage(amqpMessage, channel, 101L);

            verify(localDelegate, never()).consume(any());
            verify(channel).basicAck(eq(101L), eq(false));
        }

        @Test
        @DisplayName("eventId 是空白 → ACK + 不调用 consume")
        void shouldAckWhenEventIdIsBlank() throws Exception {
            byte[] body = buildMessageBody("   ", 11L, 22L, "API_KEY_LLM");
            when(amqpMessage.getBody()).thenReturn(body);

            consumer.onMessage(amqpMessage, channel, 102L);

            verify(localDelegate, never()).consume(any());
            verify(channel).basicAck(eq(102L), eq(false));
        }

        @Test
        @DisplayName("委托抛异常 → NACK(requeue=false) → 走 DLX")
        void shouldNackWhenDelegateThrows() throws Exception {
            byte[] body = buildMessageBody("evt-bad", 11L, 22L, "API_KEY_LLM");
            when(amqpMessage.getBody()).thenReturn(body);
            when(deduplicationService.isDuplicate("evt-bad")).thenReturn(false);
            doThrow(new RuntimeException("delegate boom"))
                    .when(localDelegate).consume(any(ExecutionCommand.class));

            consumer.onMessage(amqpMessage, channel, 103L);

            verify(deduplicationService).markFailed("evt-bad");
            verify(channel).basicNack(eq(103L), eq(false), eq(false));
            verify(channel, never()).basicAck(anyLong(), any(Boolean.class));
        }
    }

    @Nested
    @DisplayName("幂等")
    class Deduplication {

        @Test
        @DisplayName("dedup 命中 → 不调用 consume 但仍 ACK（避免重复弹回）")
        void shouldAckWithoutRecallingWhenAlreadyConsumed() throws Exception {
            byte[] body = buildMessageBody("evt-dup", 11L, 22L, "API_KEY_LLM");
            when(amqpMessage.getBody()).thenReturn(body);
            when(deduplicationService.isDuplicate("evt-dup")).thenReturn(true);

            consumer.onMessage(amqpMessage, channel, 104L);

            verify(localDelegate, never()).consume(any());
            verify(channel).basicAck(eq(104L), eq(false));
            // 命中幂等不重复标记消费成功
            verify(deduplicationService, never()).markConsumed(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Channel IO 异常透传")
    class ChannelIo {

        @Test
        @DisplayName("channel.basicAck 抛 IOException 时透传（不吞异常）")
        void shouldPropagateChannelAckException() throws Exception {
            byte[] body = buildMessageBody("evt-io", 11L, 22L, "API_KEY_LLM");
            when(amqpMessage.getBody()).thenReturn(body);
            when(deduplicationService.isDuplicate("evt-io")).thenReturn(false);
            doThrow(new java.io.IOException("ack failed")).when(channel).basicAck(anyLong(), any(Boolean.class));
            // 因为没有消费侧异常，dedup markConsumed 会被调用
            // 这里仅断言 basicAck 抛出时不静默吞掉
            try {
                consumer.onMessage(amqpMessage, channel, 105L);
                org.junit.jupiter.api.Assertions.fail("expected IOException to propagate");
            } catch (java.io.IOException expected) {
                // OK
            }
            verify(channel, atLeastOnce()).basicAck(eq(105L), eq(false));
            verify(localDelegate).consume(any(ExecutionCommand.class));
        }
    }
}
