package com.helloai.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.mqconsumer.ExecutionCommandMqMessage;
import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.RuntimeTurnExecutor;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.mq.config.RabbitMQConfig;
import org.junit.jupiter.api.AfterEach;
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
 * <p><b>用例</b>：同一 eventId 投递两次。断言全部落在<b>与「谁消费」无关的真实副作用</b>上：
 * 幂等日志只 1 行，执行记录终态 SUCCESS 且 version 走完 0→1→2 的乐观锁演进
 * （markRunning +1、markSuccess +1）。<b>不以 mock 交互计数</b>为判据——共享同一
 * Testcontainers RabbitMQ 的「无 mock」配置 B 与配置 A 构成 competing consumer，
 * 消息可能被真实 executor 抢走（it profile {@code mock-mode=true} 下真实执行同样产出
 * SUCCESS/version），故移除对 {@code verify(execute)} 计数的依赖（2026-10-07 实跑暴露）。</p>
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

        // 「业务只执行一次」的证据（不依赖「谁消费」的 mock 交互计数）：
        // 第二次投递被 Redis + DB 双层幂等拦截，故幂等日志恒为 1 行（上方 awaitUntil），
        // 且执行记录 version 恰走完 0→1→2 —— markRunning(+1)/markSuccess(+1) 各一次，
        // 重复执行会再触发 CAS 使 version 继续自增或状态回退。
        // （配置 B 的真实 executor 抢走消息时同样产出该证据，故本断言对「谁消费」不敏感。）

        // 状态机演进：PENDING(0) → markRunning(1) → markSuccess(2)
        assertEquals("SUCCESS", recordStatus(RECORD_ID));
        assertEquals(2, recordVersion(RECORD_ID));
        // 幂等日志记录状态 = CONSUMED
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM event_consumption_log
                WHERE message_id = ? AND consumer = 'MqExecutionCommandConsumer' AND status = 'CONSUMED'
                """, Integer.class, eventId));
    }

    // ==================== 清理 ====================

    /**
     * 用例后清场：本类 seed 的 9101 段数据必须回收，
     * 否则残留行会与外层共享容器（{@link ItContainers} 跨类静态单例）中其它 IT 类的
     * 同名段冲突——2026-10-05 实跑暴露：{@code TaskRunningSpecPhaseBIT} 的
     * {@code DELETE FROM sub_task} 被本类残留的 {@code agent_execution_record(sub_task_id=9101)}
     * 触发的 {@code agent_execution_record_sub_task_id_fkey} 阻断，整类 setup ERROR。
     *
     * <p>本用例跑的是<b>真实消费链</b>（MQ → 幂等 → 本地执行 → 回写），除 seed 的
     * agent_execution_record 外还会写 conversation_message（执行产出对话流）与
     * task_execution_record / task_running_spec（Running Spec 回填）等子表，
     * 故须连同这些子表一并清理。严格按 FK 依赖顺序（子 → 父）删除。</p>
     */
    @AfterEach
    void cleanup() {
        // 1) 引用 sub_task 的子表
        jdbcTemplate.update("DELETE FROM conversation_message WHERE sub_task_id = ?", SUB_TASK_ID);
        jdbcTemplate.update("DELETE FROM conversation_archive WHERE sub_task_id = ?", SUB_TASK_ID);
        jdbcTemplate.update("DELETE FROM attachment WHERE sub_task_id = ?", SUB_TASK_ID);
        jdbcTemplate.update("DELETE FROM review_record WHERE sub_task_id = ?", SUB_TASK_ID);
        jdbcTemplate.update("DELETE FROM review_recheck_log WHERE sub_task_id = ?", SUB_TASK_ID);
        jdbcTemplate.update("DELETE FROM agent_execution_record WHERE sub_task_id = ?", SUB_TASK_ID);
        // 2) 引用 task 的子表
        jdbcTemplate.update("DELETE FROM task_execution_record WHERE task_id = ?", TASK_ID);
        jdbcTemplate.update("DELETE FROM task_running_spec WHERE task_id = ?", TASK_ID);
        jdbcTemplate.update("DELETE FROM task_agent_member WHERE task_id = ?", TASK_ID);
        jdbcTemplate.update("DELETE FROM module WHERE task_id = ?", TASK_ID);
        // 3) sub_task 本体
        jdbcTemplate.update("DELETE FROM sub_task WHERE id = ?", SUB_TASK_ID);
        // 4) 引用 agent 的子表 + agent 本体
        jdbcTemplate.update("DELETE FROM agent_inbox WHERE agent_id = ?", AGENT_ID);
        jdbcTemplate.update("DELETE FROM agent_duty_lease WHERE agent_id = ?", AGENT_ID);
        jdbcTemplate.update("DELETE FROM task_agent_member WHERE agent_id = ?", AGENT_ID);
        jdbcTemplate.update("DELETE FROM agent WHERE id = ?", AGENT_ID);
        // 5) task 本体
        jdbcTemplate.update("DELETE FROM task WHERE id = ?", TASK_ID);
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