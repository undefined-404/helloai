package com.helloai.it;

import com.helloai.core.agent.service.McpToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paperclip 阶段一·3：409 抢占语义固化 —— 双 Agent 并发认领同一 PENDING 子任务。
 *
 * <p><b>被测事实</b>：{@code claimSubTask} 的原子占位
 * （{@code UPDATE sub_task SET assigned_agent_id=?, status='ASSIGNED', version=version+1
 * WHERE id=? AND status='PENDING' AND (assigned_agent_id IS NULL OR assigned_agent_id=?)}）
 * 在真实并发下保证「恰好一个赢家」；败方 reason 可被客户端识别为
 * {@code already_claimed_by_other}（等价 Paperclip 409 —— 永不重试、直接换活）。</p>
 *
 * <p><b>为什么必须真实 DB</b>：并发互斥写在 SQL 条件里（{@code WHERE status='PENDING'}），
 * 且败方归因依赖「原子占位失败后重读」——mock 无法证明「两个并发事务只有一个
 * {@code UPDATE} 命中行」，而这正是本需求的风险点（双写穿行会让双方都以为自己是赢家）。</p>
 *
 * <p><b>ID 段（2026-10-22 约定）</b>：本类用 9701 段（Testcontainers 共享库约定，
 * 避开 9101 Mq / 9201 Outbox / 9301 Cas / 9401 SpecPhaseB / 9501 CascadeDelete /
 * 9601 G006 已占用段）；{@code @AfterEach} 清场（按 FK 依赖顺序，先删
 * {@code agent_execution_record} 再删 {@code sub_task/agent/task}）。</p>
 */
@DisplayName("Paperclip 阶段一·3: claimSubTask 并发认领 409 语义")
class McpTaskClaimConcurrencyIT extends AbstractItTestBase {

    private static final long TASK_ID = 9701L;
    private static final long AGENT_A = 9701L;
    private static final long AGENT_B = 9702L;
    private static final long SUB_TASK_ID = 9701L;

    @Autowired
    private McpToolService mcpToolService;

    @Test
    @DisplayName("双 Agent 并发认领同一 PENDING：恰一赢一输，败方 reason=already_claimed_by_other（409）")
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        // 种子：task（IN_PROGRESS）+ 两个 ACTIVE EXECUTOR agent + PENDING 子任务（未分配）
        jdbcTemplate.update("""
                INSERT INTO task (id, title, status, create_by, update_by)
                VALUES (?, ?, 'IN_PROGRESS', 'it', 'it')
                """, TASK_ID, "it-task-9701");
        jdbcTemplate.update("""
                INSERT INTO agent (id, name, role, status, access_type, create_by, update_by)
                VALUES (?, ?, 'EXECUTOR', 'ACTIVE', 'CLI_CLIENT', 'it', 'it')
                """, AGENT_A, "it-agent-9701");
        jdbcTemplate.update("""
                INSERT INTO agent (id, name, role, status, access_type, create_by, update_by)
                VALUES (?, ?, 'EXECUTOR', 'ACTIVE', 'CLI_CLIENT', 'it', 'it')
                """, AGENT_B, "it-agent-9702");
        jdbcTemplate.update("""
                INSERT INTO sub_task (id, task_id, title, status, assigned_agent_id, create_by, update_by)
                VALUES (?, ?, ?, 'PENDING', NULL, 'it', 'it')
                """, SUB_TASK_ID, TASK_ID, "it-subtask-9701");

        // 并发认领：两个线程同时发起，CountDownLatch 对齐起跑线
        int concurrency = 2;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<McpToolService.ClaimSubTaskResult> futureA = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mcpToolService.claimSubTask(AGENT_A, SUB_TASK_ID);
            });
            Future<McpToolService.ClaimSubTaskResult> futureB = pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mcpToolService.claimSubTask(AGENT_B, SUB_TASK_ID);
            });
            // 等两个线程都就绪后同时放行，制造真实竞争窗口
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            McpToolService.ClaimSubTaskResult resultA = futureA.get(15, TimeUnit.SECONDS);
            McpToolService.ClaimSubTaskResult resultB = futureB.get(15, TimeUnit.SECONDS);

            // 断言：恰一个赢家，败方 reason = already_claimed_by_other（409 语义）
            boolean aWon = resultA.isClaimed();
            boolean bWon = resultB.isClaimed();
            assertTrue(aWon ^ bWon, "并发认领必须恰好一个赢家（XOR），实际 aWon=" + aWon + ", bWon=" + bWon);

            McpToolService.ClaimSubTaskResult loser = aWon ? resultB : resultA;
            assertEquals("already_claimed_by_other", loser.getReason(),
                    "败方 reason 必须是 already_claimed_by_other（等价 Paperclip 409，客户端应换活）");

            // DB 终态：assigned_agent_id 指向赢家且非空，状态 ASSIGNED
            String assigned = jdbcTemplate.queryForObject(
                    "SELECT assigned_agent_id FROM sub_task WHERE id = ?", String.class, SUB_TASK_ID);
            assertFalse(assigned == null || assigned.isBlank(), "赢家必须被写入 assigned_agent_id");
            Long winnerId = aWon ? AGENT_A : AGENT_B;
            assertEquals(String.valueOf(winnerId), assigned, "DB 终态 assigned_agent_id 必须指向赢家");
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM sub_task WHERE id = ?", String.class, SUB_TASK_ID);
            assertEquals("ASSIGNED", status, "赢家认领后状态应为 ASSIGNED（随后门面内 start 推 IN_PROGRESS 亦合法）");
        } finally {
            pool.shutdownNow();
        }
    }

    @AfterEach
    void cleanup() {
        // 按 FK 依赖顺序清场（子 → 父）：agent_execution_record → sub_task → agent → task
        jdbcTemplate.update("DELETE FROM agent_execution_record WHERE sub_task_id = ?", SUB_TASK_ID);
        jdbcTemplate.update("DELETE FROM sub_task WHERE id = ?", SUB_TASK_ID);
        jdbcTemplate.update("DELETE FROM agent WHERE id = ?", AGENT_A);
        jdbcTemplate.update("DELETE FROM agent WHERE id = ?", AGENT_B);
        jdbcTemplate.update("DELETE FROM task WHERE id = ?", TASK_ID);
        // isToolEnabled 对 DEFAULT_EXECUTOR_TOOLS 自动补行的 agent_mcp_server 一并回收
        jdbcTemplate.update("DELETE FROM agent_mcp_server WHERE agent_id IN (?, ?)", AGENT_A, AGENT_B);
    }
}
