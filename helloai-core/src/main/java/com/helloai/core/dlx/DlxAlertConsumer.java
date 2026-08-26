package com.helloai.core.dlx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.mq.config.RabbitMQConfig;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 死信队列告警消费者（Step 4，v1.2 分布式健壮性改造）。
 *
 * <p>消费 dlxQueue 上的死信消息，固定顺序为「台账 → 告警 → ACK」：先落死信台账
 * （{@code mq_dead_letter_archive}，V60）再打告警日志后 ACK。死信消息 ACK 后即从队列消失，
 * 台账是唯一可追溯数据源（后续重放工具按 original_routing_key 读表重发，本轮只预留列位）。</p>
 *
 * <p>dlxQueue 未挂 DLX：消费异常时 {@code basicNack(requeue=false)}，消息被直接丢弃，
 * 台账已在写库阶段兜底；任何情况下不得 requeue（避免死信在 dlxQueue 上反复循环）。</p>
 */
@Slf4j
@Component
public class DlxAlertConsumer {

    /** 消息体快照截断上限（64KB），防台账膨胀（载荷 ≤10KB 为项目规范前提，超长截断即可）。 */
    private static final int BODY_CAP = 64 * 1024;
    /** 告警日志 body 预览上限（字符），防日志刷屏。 */
    private static final int LOG_PREVIEW_CAP = 500;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DlxAlertConsumer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.DLX_QUEUE, ackMode = "MANUAL")
    public void onDeadLetter(Message message, Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            MessageProperties props = message.getMessageProperties();
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            // 1) 先落台账（失败仅记 error，不阻断告警；极小窗口容忍丢台账，告警日志本身仍落盘可捞）
            try {
                jdbcTemplate.update("""
                        INSERT INTO mq_dead_letter_archive
                        (original_exchange, original_routing_key, first_death_exchange,
                         first_death_queue, first_death_reason, headers, body)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)""",
                        props.getReceivedExchange(), props.getReceivedRoutingKey(),
                        props.getHeader("x-first-death-exchange"),
                        props.getHeader("x-first-death-queue"),
                        props.getHeader("x-first-death-reason"),
                        serializeHeaders(props), truncate(body));
            } catch (Exception e) {
                log.error("死信台账写入失败（仅记录，不阻断告警）", e);
            }
            // 2) 告警（人工介入或重放的依据）
            log.error("【死信告警】消息进入 DLX，需人工介入或重放: originalExchange={}, originalRoutingKey={}, body={}",
                    props.getReceivedExchange(), props.getReceivedRoutingKey(),
                    body.length() > LOG_PREVIEW_CAP ? body.substring(0, LOG_PREVIEW_CAP) + "...(截断)" : body);
            // 3) 后 ACK（防告警消费者自身异常把死信反复打回造成循环）
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("死信告警消费异常", e);
            channel.basicNack(tag, false, false);
        }
    }

    /** 消息头快照序列化为 JSON（写 JSONB 列）；序列化失败记录 warn 并写 null。 */
    private String serializeHeaders(MessageProperties props) {
        try {
            return objectMapper.writeValueAsString(props.getHeaders());
        } catch (Exception e) {
            log.warn("死信消息头序列化失败（headers 列写 null）", e);
            return null;
        }
    }

    /** 消息体截断至 64KB，防台账膨胀。 */
    private String truncate(String body) {
        if (body == null || body.length() <= BODY_CAP) {
            return body;
        }
        return body.substring(0, BODY_CAP);
    }
}