package com.helloai.core.review.mqconsumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.constant.FinalReportReviewConst;
import com.helloai.core.review.service.FinalReportReviewService;
import com.helloai.mq.config.RabbitMQConfig;
import com.helloai.mq.consumer.AbstractIdempotentConsumer;
import com.helloai.mq.service.MessageDeduplicationService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 最终报告审查 L2 消费者（§12.2 审查链三级容错 L2）。
 *
 * <p><b>为什么需要它</b>：改造前报告审查只有 L1（AFTER_COMMIT 内存事件 + 本地线程池）。
 * L1 的触发链依赖进程内存——JVM 重启、线程池丢失、或事件在提交后恰好落空，
 * 报告就永久停在 {@code REVIEWING} 且无任何重投/补偿入口（实测已发生，需人工介入）。
 * 本消费者承接 Outbox 持久化后的审查请求，使触发具备「重启不丢 + 可重投」。</p>
 *
 * <p><b>与 L1 的关系</b>：L1 与 L2 会各触发一次审查，重复由
 * {@code FinalReportReviewServiceImpl} 的 Redis 防双审锁 + {@code final_report_status}
 * 状态守卫消解（先到者收敛 DONE 后，后到者幂等跳过），与子任务核验链三级容错同构。</p>
 *
 * <p><b>消费骨架</b>（与 {@code MqReviewCommandConsumer} / {@code MqExecutionCommandConsumer} 同款约束）：</p>
 * <ol>
 *   <li>仅在 {@code helloai.mq.report-review.consumer-enabled=true} 时生效
 *       （yml 默认开启）；关闭后整个 Bean 不存在，L1 与 L3 路径不受影响</li>
 *   <li>消息体为 {@code AgentOutboxService.createReportReviewEvent} 的 payload JSON
 *       （eventId / taskId / reportTime / attempt / reportLength）；
 *       解析失败或缺 taskId 按 ACK 处理（坏消息不阻塞队列，写死信也无意义）</li>
 *   <li>MANUAL ACK：消费成功 → basicAck；消费失败 → basicNack(requeue=false) 走 DLX
 *       （失败计数由父类 markFailed 写入 event_consumption_log，不反复弹回主队列）</li>
 *   <li>幂等由父类 {@link AbstractIdempotentConsumer#tryConsume} 提供 Redis + DB 双层去重：
 *       幂等键取 payload.eventId——同一 Outbox 事件重投不重复消费，
 *       而同任务的首次生成 / 驳回返工 / 兜底重投各自持有独立 eventId，互不误伤</li>
 * </ol>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "helloai.mq.report-review.consumer-enabled", havingValue = "true",
        matchIfMissing = true)
public class MqFinalReportReviewConsumer extends AbstractIdempotentConsumer {

    private final FinalReportReviewService finalReportReviewService;

    public MqFinalReportReviewConsumer(JdbcTemplate jdbcTemplate,
                                       ObjectMapper objectMapper,
                                       MessageDeduplicationService deduplicationService,
                                       FinalReportReviewService finalReportReviewService) {
        super(jdbcTemplate, objectMapper, deduplicationService);
        this.finalReportReviewService = finalReportReviewService;
    }

    /**
     * RabbitMQ 入口：解析 payload → 幂等 → 触发报告审查 → ACK / NACK。
     */
    @RabbitListener(queues = RabbitMQConfig.REPORT_REVIEW_QUEUE, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        Map<?, ?> payload;
        try {
            payload = objectMapper.readValue(message.getBody(), Map.class);
        } catch (Exception e) {
            log.warn("MQ 报告审查命令解析失败，跳过(ACK): body={}, error={}",
                    new String(message.getBody(), StandardCharsets.UTF_8), e.getMessage());
            channel.basicAck(tag, false);
            return;
        }
        if (payload == null) {
            channel.basicAck(tag, false);
            return;
        }
        Long taskId = toLong(payload.get(FinalReportReviewConst.FIELD_TASK_ID));
        if (taskId == null) {
            log.warn("MQ 报告审查命令缺少 taskId，跳过(ACK): body={}",
                    new String(message.getBody(), StandardCharsets.UTF_8));
            channel.basicAck(tag, false);
            return;
        }
        OffsetDateTime reportTime = toOffsetDateTime(payload.get(FinalReportReviewConst.FIELD_REPORT_TIME));
        int attempt = toInt(payload.get(FinalReportReviewConst.FIELD_ATTEMPT), 1);
        int reportLength = toInt(payload.get(FinalReportReviewConst.FIELD_REPORT_LENGTH), 0);
        String eventId = toText(payload.get(FinalReportReviewConst.FIELD_EVENT_ID));
        String messageId = (eventId != null && !eventId.isBlank())
                ? eventId : FinalReportReviewConst.DEDUP_PREFIX + taskId + ":" + reportTime;

        boolean processed = false;
        try {
            processed = tryConsume(messageId, FinalReportReviewConst.CONSUMER_NAME,
                    mdcOf(taskId),
                    () -> finalReportReviewService.review(taskId, reportTime, attempt, reportLength));
        } catch (Exception e) {
            log.error("MQ 报告审查命令消费失败: messageId={}, taskId={}", messageId, taskId, e);
            processed = false;
        }

        if (processed) {
            channel.basicAck(tag, false);
            log.debug("MQ 报告审查命令 ACK: messageId={}, taskId={}", messageId, taskId);
        } else {
            channel.basicNack(tag, false, false);
            log.warn("MQ 报告审查命令 NACK (→ DLX): messageId={}, taskId={}", messageId, taskId);
        }
    }

    /** 组装消费期 MDC 上下文：报告审查消息自带 taskId。 */
    private static Map<String, String> mdcOf(Long taskId) {
        Map<String, String> mdc = new HashMap<>();
        if (taskId != null) {
            mdc.put(AbstractIdempotentConsumer.MDC_TASK_ID, String.valueOf(taskId));
        }
        return mdc;
    }

    /** Jackson 反序列化 Map 时小整数默认 Integer，统一转 Long。 */
    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (Exception ignored) {
                // 落回默认值
            }
        }
        return defaultValue;
    }

    /**
     * reportTime 还原：生产侧显式落 ISO-8601（带偏移）字符串，此处
     * {@link OffsetDateTime#parse} 确定性还原。解析失败返回 null——由审查体的陈旧守卫
     * 判定为陈旧并丢弃（宁可跳过一次审查，也不误用错误锚点覆盖新链状态）。
     */
    private static OffsetDateTime toOffsetDateTime(Object value) {
        String text = toText(value);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (Exception e) {
            log.warn("MQ 报告审查命令 reportTime 解析失败: raw={}, err={}", text, e.getMessage());
            return null;
        }
    }

    private static String toText(Object value) {
        return value != null ? value.toString() : null;
    }
}
