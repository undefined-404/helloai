package com.helloai.core.review.mqconsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.review.service.SubTaskReviewService;
import com.helloai.mq.service.MessageDeduplicationService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 D：{@link MqReviewCommandConsumer} 单元测试（REVIEWER 审查命令 L2 MQ consumer）。
 *
 * <p>覆盖 7 类行为：</p>
 * <ol>
 *     <li>正常消息（payload 含 eventId）：解析 → reviewSubTask(subTaskId, agentId) → 幂等标记 → ACK</li>
 *     <li>老消息（payload 无 eventId）：幂等键回退 {@code sub_task.review:{subTaskId}}</li>
 *     <li>消息体无法解析：ACK（坏消息不阻塞队列）+ 不触发核验</li>
 *     <li>缺 subTaskId：ACK + 不触发核验</li>
 *     <li>agentId=0（null 占位）：归一为 null 传入核验</li>
 *     <li>幂等命中：直接 ACK，不重复触发核验</li>
 *     <li>核验抛异常：NACK(requeue=false) → DLX</li>
 * </ol>
 *
 * <p>本测试不启动 Spring 容器与 RabbitMQ，全部基于 Mockito + 真实 Jackson 序列化。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MqReviewCommandConsumer")
class MqReviewCommandConsumerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private MessageDeduplicationService deduplicationService;

    @Mock
    private SubTaskReviewService subTaskReviewService;

    @Mock
    private Message amqpMessage;

    @Mock
    private Channel channel;

    private MqReviewCommandConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MqReviewCommandConsumer(
                jdbcTemplate, MAPPER, deduplicationService, subTaskReviewService);
    }

    /** 真实消息体构造器（模拟 AgentOutboxService.createEvent 的 payload）。 */
    private byte[] buildBody(String eventId, Object subTaskId, Object agentId) {
        Map<String, Object> payload = new HashMap<>();
        if (eventId != null) {
            payload.put("eventId", eventId);
        }
        payload.put("subTaskId", subTaskId);
        payload.put("taskId", 10L);
        payload.put("status", "REVIEW");
        payload.put("agentId", agentId);
        try {
            return MAPPER.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("正常消息流")
    class HappyPath {

        @Test
        @DisplayName("payload 含 eventId → reviewSubTask(subTaskId, agentId) → eventId 幂等键 → ACK")
        void shouldReviewAndAckOnValidMessage() throws Exception {
            when(amqpMessage.getBody()).thenReturn(buildBody("evt-1", 11L, 22L));
            when(deduplicationService.isDuplicate("evt-1")).thenReturn(false);

            consumer.onMessage(amqpMessage, channel, 99L);

            verify(subTaskReviewService).reviewSubTask(11L, 22L);
            verify(deduplicationService).markConsumed("evt-1", MqReviewCommandConsumer.CONSUMER_NAME);
            verify(channel).basicAck(eq(99L), eq(false));
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("老消息无 eventId → 幂等键回退 sub_task.review:{subTaskId} → ACK")
        void shouldFallbackLegacyIdWithoutEventId() throws Exception {
            when(amqpMessage.getBody()).thenReturn(buildBody(null, 11L, 22L));
            when(deduplicationService.isDuplicate("sub_task.review:11")).thenReturn(false);

            consumer.onMessage(amqpMessage, channel, 99L);

            verify(subTaskReviewService).reviewSubTask(11L, 22L);
            verify(deduplicationService).markConsumed("sub_task.review:11", MqReviewCommandConsumer.CONSUMER_NAME);
            verify(channel).basicAck(eq(99L), eq(false));
        }

        @Test
        @DisplayName("agentId=0（null 占位）→ 归一为 null 传入核验")
        void shouldNormalizeZeroAgentIdToNull() throws Exception {
            when(amqpMessage.getBody()).thenReturn(buildBody("evt-3", 11L, 0L));
            when(deduplicationService.isDuplicate("evt-3")).thenReturn(false);

            consumer.onMessage(amqpMessage, channel, 99L);

            verify(subTaskReviewService).reviewSubTask(11L, null);
            verify(channel).basicAck(eq(99L), eq(false));
        }
    }

    @Nested
    @DisplayName("坏消息与防御")
    class DefensivePaths {

        @Test
        @DisplayName("消息体无法解析 → ACK + 不触发核验")
        void shouldAckOnUnparseableBody() throws Exception {
            when(amqpMessage.getBody()).thenReturn("not-a-json".getBytes(StandardCharsets.UTF_8));

            consumer.onMessage(amqpMessage, channel, 99L);

            verify(subTaskReviewService, never()).reviewSubTask(anyLong(), any());
            verify(channel).basicAck(eq(99L), eq(false));
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        }

        @Test
        @DisplayName("缺 subTaskId → ACK + 不触发核验")
        void shouldAckWhenSubTaskIdMissing() throws Exception {
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventId", "evt-4");
            payload.put("status", "REVIEW");
            when(amqpMessage.getBody()).thenReturn(MAPPER.writeValueAsBytes(payload));

            consumer.onMessage(amqpMessage, channel, 99L);

            verify(subTaskReviewService, never()).reviewSubTask(anyLong(), any());
            verify(channel).basicAck(eq(99L), eq(false));
        }
    }

    @Nested
    @DisplayName("幂等与失败")
    class IdempotencyAndFailure {

        @Test
        @DisplayName("幂等命中（同 eventId 已消费）→ 直接 ACK，不重复触发核验")
        void shouldSkipDuplicateAndAck() throws Exception {
            when(amqpMessage.getBody()).thenReturn(buildBody("evt-5", 11L, 22L));
            when(deduplicationService.isDuplicate("evt-5")).thenReturn(true);

            consumer.onMessage(amqpMessage, channel, 99L);

            verify(subTaskReviewService, never()).reviewSubTask(anyLong(), any());
            verify(channel).basicAck(eq(99L), eq(false));
        }

        @Test
        @DisplayName("核验抛异常 → NACK(requeue=false) → DLX，不 ACK")
        void shouldNackOnReviewException() throws Exception {
            when(amqpMessage.getBody()).thenReturn(buildBody("evt-6", 11L, 22L));
            when(deduplicationService.isDuplicate("evt-6")).thenReturn(false);
            doThrow(new RuntimeException("review down"))
                    .when(subTaskReviewService).reviewSubTask(11L, 22L);

            consumer.onMessage(amqpMessage, channel, 99L);

            verify(deduplicationService).markFailed("evt-6");
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            verify(channel).basicNack(eq(99L), eq(false), eq(false));
        }
    }
}
