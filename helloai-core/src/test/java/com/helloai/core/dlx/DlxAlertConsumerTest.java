package com.helloai.core.dlx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DlxAlertConsumer} 单元测试（Step 4 死信台账）。
 *
 * <p>覆盖：台账落库成功 → ACK / 台账落库失败仍 ACK 不炸（告警不阻断）/ 消费异常 → NACK(requeue=false)。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DlxAlertConsumer")
class DlxAlertConsumerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private Channel channel;

    private DlxAlertConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DlxAlertConsumer(jdbcTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("台账落库成功 → 先写库再告警 ACK")
    void shouldAckAfterArchiveInserted() throws Exception {
        consumer.onDeadLetter(deadLetterMessage(), channel, 1L);

        // 台账 INSERT 被调用，且带死信头/路由信息
        verify(jdbcTemplate).update(contains("INSERT INTO mq_dead_letter_archive"),
                any(Object[].class));
        // 后 ACK
        verify(channel).basicAck(eq(1L), eq(false));
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("台账落库失败 → 仅记 error，仍 ACK 不炸（告警不能被 DB 抖动阻断）")
    void shouldStillAckWhenArchiveInsertFails() throws Exception {
        doThrow(new RuntimeException("db down"))
                .when(jdbcTemplate).update(anyString(), any(Object[].class));

        consumer.onDeadLetter(deadLetterMessage(), channel, 1L);

        verify(channel).basicAck(eq(1L), eq(false));
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("消费异常 → NACK(requeue=false)（dlxQueue 未挂 DLX，任何情况下不得 requeue）")
    void shouldNackWithoutRequeueOnConsumeException() throws Exception {
        doThrow(new IOException("channel closed"))
                .when(channel).basicAck(eq(1L), eq(false));

        consumer.onDeadLetter(deadLetterMessage(), channel, 1L);

        verify(channel).basicNack(eq(1L), eq(false), eq(false));
    }

    /** 构造一条带 x-first-death-* 头与路由信息的死信消息。 */
    private Message deadLetterMessage() {
        byte[] body = "{\"eventId\":\"e-100\",\"subTaskId\":1}".getBytes(StandardCharsets.UTF_8);
        MessageProperties props = new MessageProperties();
        props.setReceivedExchange("helloai.agent.exchange");
        props.setReceivedRoutingKey("agent.executor.123");
        props.setHeader("x-first-death-exchange", "helloai.agent.exchange");
        props.setHeader("x-first-death-queue", "helloai.executor.queue");
        props.setHeader("x-first-death-reason", "rejected");
        return new Message(body, props);
    }
}