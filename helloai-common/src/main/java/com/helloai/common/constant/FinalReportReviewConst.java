package com.helloai.common.constant;

/**
 * 最终整合报告审查链的 MQ 契约常量（§12.2 审查链三级容错 L2）。
 *
 * <p>报告审查此前只有 L1（内存事件 + 本地线程池），进程重启即丢、无重投无补偿。
 * 本常量定义 L2（Outbox → MQ）的投递契约：发布端 {@code AgentOutboxServiceImpl} 与
 * 消费端 {@code MqFinalReportReviewConsumer}、配置端 {@code RabbitMQConfig} 共用同一份字面量，
 * 避免三处硬编码漂移。</p>
 *
 * <p><b>路由键设计</b>：独立前缀 {@code agent.report-review.}，<b>不复用</b>
 * {@code agent.reviewer.*}——后者绑定到 {@code REVIEWER_QUEUE} 且被
 * {@code MqReviewCommandConsumer} 按「必须含 subTaskId」消费，报告审查消息无 subTaskId，
 * 复用会被该消费者当作坏消息直接 ACK 丢弃（静默丢失）。独立前缀 + 独立队列才能让
 * 两类审查各自演进。</p>
 */
public final class FinalReportReviewConst {

    private FinalReportReviewConst() {
    }

    /** Outbox 路由键：报告审查请求（绑定到报告审查专用队列）。 */
    public static final String ROUTING_KEY = "agent.report-review.assigned";

    /** Outbox 事件类型（agent_outbox_event.event_type）。 */
    public static final String EVENT_TYPE = "task.final_report.review_requested";

    /** 消费者名称（幂等日志与 event_consumption_log.consumer）。 */
    public static final String CONSUMER_NAME = "MqFinalReportReviewConsumer";

    /** payload 字段名：任务 ID。 */
    public static final String FIELD_TASK_ID = "taskId";
    /** payload 字段名：报告写回时间（ISO-8601 字符串，陈旧守卫锚点）。 */
    public static final String FIELD_REPORT_TIME = "reportTime";
    /** payload 字段名：生成轮次。 */
    public static final String FIELD_ATTEMPT = "attempt";
    /** payload 字段名：报告正文字符数。 */
    public static final String FIELD_REPORT_LENGTH = "reportLength";
    /** payload 字段名：事件 ID（消息幂等键）。 */
    public static final String FIELD_EVENT_ID = "eventId";

    /**
     * 幂等键前缀。多轮审查（首次生成 / 驳回返工 / 兜底重投）各自独立消息，
     * 因此以 outbox {@code eventId} 为幂等键（生产侧同一事件重投不重复消费）。
     */
    public static final String DEDUP_PREFIX = "task.final_report.review:";
}
