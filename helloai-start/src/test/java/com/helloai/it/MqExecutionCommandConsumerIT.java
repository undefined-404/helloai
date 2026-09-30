package com.helloai.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.RuntimeTurnExecutor;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.mq.config.RabbitMQConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2：MQ 执行命令幂等消费（审计建议 #5-2）。
 *
 * <p><b>被测事实</b>：{@code MqExecutionCommandConsumer} 走真实 RabbitMQ {@code @RabbitListener}
 * （MANUAL ACK）→ {@code AbstractIdempotentConsumer#tryConsume}
 * → Redis 快路径 + {@code event_consumption_log} DB 兜底 → 委托
 * {@code LocalExecutionCommandConsumer} 执行链（markRunning CAS → timeline →
 * AgentRuntime → markSuccess）——除最外层 LLM 边界外全部真实。</p>
 *
 * <p><b>外部边界隔离</b>：{@link RuntimeTurnExecutor} 以 {@code @MockBean} 替换
 * （其内部 AgentLoop→LLM 属执行器外部边界），mock 返回 SUCCESS 结果；
 * 这正是审计批评「@MockBean 挂集成测试」之外的正当用途——只隔离不可控外部调用，
 * MQ / 幂等 / CAS / DB 落库全部走真实链路。</p>
 *
 * <p><b>用例</b>：同一 eventId 投递两次。断言：业务逻辑只执行 1 次
 * （execute 调用 1 次），幂等日志只 1 行，执行记录终态 SUCCESS 且 version 走完
 * 0→1→2 的乐观锁演进（markRunning +1、markSuccess +1）。</p>
 */
@DisplayName("B2: MQ 执行命令幂等消费")
class MqExecutionCommandConsumerIT extends AbstractItTestBase {

    private static final long TASK_ID = 9101L;
    private static final long AGENT_ID = 9101L;
    private static final long SUB_TASK_ID = 9101L;
    private static final long RECORD_ID = 9101L;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** 执行器外部边界（LLM 调用）mock 隔离；MockitoBean 会替换原 Bean 并仍被 List<AgentRuntime> 收集。 */
    @MockitoBean
    private RuntimeTurnExecutor runtimeTurnExecutor;

    @Test
    @DisplayName("同一事件投递两次：业务执行仅 1 次，幂等日志仅 1 行，状态机正常演进")
    void duplicateDeliveryConsumesOnce() throws Exception {
        // 前置数据：task / agent / sub_task(ASSIGNED) + PENDING 执行记录（version=0）
        seedAssignedSubTask(TASK_ID, AGENT_ID, SUB_TASK_ID);
        String eventId = UUID.randomUUID().toString().replace("-", "");
        seedPendingExecutionRecord(RECORD_ID, eventId, SUB_TASK_ID, AGENT_ID);

        when(runtimeTurnExecutor.execute(any(AgentContext.class)))
                .thenReturn(AgentExecutionResult.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .output("it-mock-output")
                        .build());

        // 第一次投递（消费成功路径）
        rabbitTemplate.send(RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE,
                "execution.command.created",
                buildMessage(eventId));

        // 等待：幂等日志落 1 行 + 执行记录到 SUCCESS（消费链异步，轮询等待）
        awaitUntil("首次投递应完成消费（event_consumption_log=1）", 20,
                () -> consumptionLogCount(eventId) == 1);
        awaitUntil("执行记录应到 SUCCESS", 20,
                () -> recordStatus(RECORD_ID).equals("SUCCESS"));

        // 第二次投递（同一 eventId：幂等跳过）
        rabbitTemplate.send(RabbitMQConfig.EXECUTION_COMMAND_EXCHANGE,
                "execution.command.created",
                buildMessage(eventId));

        // 幂等日志仍 1 行；再给异步消费留一点时间后断言不增殖
        awaitUntil("重复投递应被幂等拦截（event_consumption_log 仍=1）", 10,
                () -> {
                    awaitSilently(1500);
                    return consumptionLogCount(eventId) == 1;
                });

        // 业务逻辑只执行了一次（第二次被 Redis + DB 双层幂等拦截）。
        // 按 subTaskId 精确匹配本用例调用：B2/B3 共享同一 @MockitoBean 实例
        // （TestContext 缓存复用同配置上下文），类间计数会累计（2026-09-29 实跑暴露）
        verify(runtimeTurnExecutor, times(1)).execute(argThat(ctx -> ctx.getSubTaskId() == SUB_TASK_ID));

        // 状态机演进：PENDING(0) → markRunning(1) → markSuccess(2)
        assertEquals("SUCCESS", recordStatus(RECORD_ID));
        assertEquals(2, recordVersion(RECORD_ID));
        // 幂等日志记录状态 = CONSUMED
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM event_consumption_log
                WHERE message_id = ? AND consumer = 'MqExecutionCommandConsumer' AND status = 'CONSUMED'
                """, Integer.class, eventId));
    }

    // ==================== 工具 ====================

    /** 构造与真实 Publisher 完全同构的消息（JSON body + eventId 消息头 + 持久化投递）。 */
    private Message buildMessage(String eventId) throws Exception {
        ExecutionCommandMqMessage payload = ExecutionCommandMqMessage.builder()
                .recordId(RECORD_ID)
                .eventId(eventId)
                .subTaskId(SUB_TASK_ID)
                .agentId(AGENT_ID)
                .trigger("assigned")
                .accessType("CLI_CLIENT")
                .requiredSkills(List.of())
                .build();
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setMessageId(eventId);
        props.setCorrelationId(eventId);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(objectMapper.writeValueAsBytes(payload), props);
    }

    private int consumptionLogCount(String eventId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_consumption_log WHERE message_id = ?",
                Integer.class, eventId);
        return n == null ? 0 : n;
    }

    private String recordStatus(long recordId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM agent_execution_record WHERE id = ?",
                String.class, recordId);
    }

    private int recordVersion(long recordId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT version FROM agent_execution_record WHERE id = ?",
                Integer.class, recordId);
        return v == null ? -1 : v;
    }

    private static void awaitSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}