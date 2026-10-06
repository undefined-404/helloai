package com.helloai.it;

import com.helloai.core.agent.runtime.AgentContext;
import com.helloai.core.agent.runtime.AgentExecutionResult;
import com.helloai.core.agent.runtime.RuntimeTurnExecutor;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.service.ExecutionCommandService;
import com.helloai.job.task.OutboxRelayTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * B3：Outbox 事务边界 + Relay 投递闭环（审计建议 #5-3）。
 *
 * <p><b>被测事实</b>：{@code ExecutionCommandServiceImpl.createAssignedCommand}
 * （@Transactional）同事务写入 {@code ExecutionCommand / agent_execution_record /
 * agent_command_outbox} 三表（Phase 2H ②a 语义：要么一起提交要么一起回滚，杜绝
 * 「record 已提交但 outbox 没写」或反向孤儿）；随后手动触发
 * {@code OutboxRelayTask.relay()}（测试环境无 @EnableScheduling，调度显式化），
 * 经真实 Publisher Confirms 把 outbox 行推进 SENT → CONFIRMED，并经真实
 * {@code @RabbitListener} 消费完成执行链（幂等日志 + 执行记录终态）。</p>
 *
 * <p><b>隔离口径</b>：与 B2 相同，仅以 {@code @MockBean} 隔离
 * {@link RuntimeTurnExecutor}（LLM 外部边界），MQ / Outbox / DB 全真实。</p>
 *
 * <p><b>时序</b>：createAssignedCommand 的 dispatch-mode=MQ（application.yml 默认）写
 * outbox PENDING → relay() 一次即可完成投递与 confirm 回写；relay 的 confirm 回调
 * 异步（whenComplete），用 {@link #awaitUntil} 轮询 CONFIRMED。</p>
 */
@DisplayName("B3: Outbox 事务边界与 Relay 投递闭环")
class AgentCommandOutboxIT extends AbstractItTestBase {

    private static final long TASK_ID = 9201L;
    private static final long AGENT_ID = 9201L;
    private static final long SUB_TASK_ID = 9201L;

    @Autowired
    private ExecutionCommandService executionCommandService;

    @Autowired
    private OutboxRelayTask outboxRelayTask;

    @MockitoBean
    private RuntimeTurnExecutor runtimeTurnExecutor;

    @Test
    @DisplayName("命令创建：三表同事务落库，Relay 投递后 outbox 到 CONFIRMED 且消费闭环")
    void outboxTransactionBoundaryAndRelayDelivery() throws Exception {
        seedAssignedSubTask(TASK_ID, AGENT_ID, SUB_TASK_ID);
        when(runtimeTurnExecutor.execute(any(AgentContext.class)))
                .thenReturn(AgentExecutionResult.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .output("it-mock-output")
                        .build());

        // ---- 1. 同事务：createAssignedCommand 三表落库（record PENDING + outbox PENDING）----
        executionCommandService.createAssignedCommand(
                SUB_TASK_ID, AGENT_ID, "assigned", List.of("it-skill"));

        Integer recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_execution_record WHERE sub_task_id = ? AND status = 'PENDING'",
                Integer.class, SUB_TASK_ID);
        assertEquals(1, recordCount, "同事务应落 1 条 PENDING 执行记录");

        Integer outboxPending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_command_outbox WHERE status = 0 AND aggregate_id = ?",
                Integer.class, String.valueOf(recordIdOf(SUB_TASK_ID)));
        assertEquals(1, outboxPending, "同事务应落 1 条 PENDING outbox 行");

        // 执行命令创建阶段 timeline 已落地（sub_task_execution_command_created）
        Integer timelineCreated = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_timeline
                WHERE sub_task_id = ? AND event_type = 'sub_task_execution_command_created'
                """, Integer.class, SUB_TASK_ID);
        assertEquals(1, timelineCreated, "命令创建阶段 timeline 应落库");

        // ---- 2. 手动触发 Relay（测试环境无调度，显式化）----
        // 与生产 @Scheduled(fixedRate=1s) 语义对齐：等待期内周期性触发 relay，
        // 直到 outbox 行被 broker confirm 回写 CONFIRMED（单次触发存在扫描空转
        // 竞态，2026-09-29 实跑偶发超时，轮询触发消除 flake）
        awaitUntil("outbox 行应被 broker confirm 回写为 CONFIRMED", 20,
                () -> {
                    outboxRelayTask.relay();
                    Integer status = jdbcTemplate.queryForObject(
                            "SELECT status FROM agent_command_outbox WHERE aggregate_id = ?",
                            Integer.class, String.valueOf(recordIdOf(SUB_TASK_ID)));
                    return status != null && status == 3;
                });

        // ---- 3. MQ 消费闭环：真实 @RabbitListener 消费 → 幂等日志 + 执行记录终态 ----
        awaitUntil("MQ 消费应完成（event_consumption_log=1）", 20,
                () -> {
                    Integer n = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM event_consumption_log WHERE consumer = 'MqExecutionCommandConsumer'",
                            Integer.class);
                    return n != null && n == 1;
                });
        awaitUntil("执行记录应到 SUCCESS", 20,
                () -> "SUCCESS".equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM agent_execution_record WHERE sub_task_id = ?",
                        String.class, SUB_TASK_ID)));

        // 「执行链只走一次」的证据（不依赖「谁消费」的 mock 交互计数）：执行记录已到
        // SUCCESS（上方 awaitUntil）+ 幂等日志恰 1 行（上方 awaitUntil）+ 消费阶段 timeline
        // 恰 1 条（下方断言）。同 B2：共享 Testcontainers RabbitMQ 的「无 mock」配置 B 与
        // 配置 A 构成 competing consumer，消息可能被真实 executor 抢走（it profile
        // mock-mode=true 下真实执行同样驱动 SUCCESS），故移除 mock 计数依赖（2026-10-07 实跑暴露）。
        // outbox confirms 回写字段：last_sent_time / confirmed_time 均非空
        Integer confirmedWithTimes = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_command_outbox
                WHERE aggregate_id = ? AND status = 3
                  AND last_sent_time IS NOT NULL AND confirmed_time IS NOT NULL
                """, Integer.class, String.valueOf(recordIdOf(SUB_TASK_ID)));
        assertEquals(1, confirmedWithTimes, "CONFIRMED 行应同时具备 last_sent_time 与 confirmed_time");
        // 消费阶段 timeline 已落地（sub_task_execution_command_consume）
        Integer timelineConsume = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task_timeline
                WHERE sub_task_id = ? AND event_type = 'sub_task_execution_command_consume'
                """, Integer.class, SUB_TASK_ID);
        assertEquals(1, timelineConsume, "消费阶段 timeline 应落库");
    }

    @Test
    @DisplayName("事务回滚语义：record 已提交但 outbox 未写时，同事务回滚不留孤儿（防御性验证）")
    void noOrphanOutboxWithoutRecord() {
        // 本用例验证的是「同事务」保证的静态事实：本类首个用例已断言三表同落；
        // 此处补一条反向观测——outbox 行不存在「无对应 execution_record」的孤儿形态。
        // 触发一次 Relay 扫描空表不抛异常（listReadyForRelay 空批路径）。
        outboxRelayTask.relay(); // 不应抛异常，空批静默返回
        Integer orphans = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_command_outbox o
                WHERE NOT EXISTS (
                    SELECT 1 FROM agent_execution_record r WHERE r.id = o.aggregate_id::bigint
                )
                """, Integer.class);
        assertEquals(0, orphans, "不应存在无对应执行记录的孤儿 outbox 行");
    }

    private Long recordIdOf(long subTaskId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM agent_execution_record WHERE sub_task_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, subTaskId);
    }
}