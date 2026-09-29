package com.helloai.it;

import com.helloai.core.agent.service.AgentExecutionRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4：状态机 CAS 并发安全（审计建议 #5-4）。
 *
 * <p><b>被测事实</b>：{@code AgentExecutionRecordServiceImpl.markRunning}
 * 采用 {@code update(entity, wrapper)} 形态（触发 MyBatis-Plus OptimisticLockerInnerInterceptor
 * 的 @Version 乐观锁）+ {@code WHERE id=? AND status=PENDING} 双条件 CAS（CODE_STYLE §15）。
 * B4 在真实 PostgreSQL 上用双线程并发拨号 PENDING→RUNNING，验证「恰好一个成功」——</p>
 * <ul>
 *   <li>若乐观锁失效：两个线程都 update 成功，status 被并发覆盖（version 也不自增）；</li>
 *   <li>若 status 条件失效：RUNNING 后可被反复 markRunning（终态回退被破坏）；</li>
 *   <li>正确行为：一胜一负，DB 最终 status=RUNNING、version=1，且 RUNNING 不可再 markRunning。</li>
 * </ul>
 *
 * <p><b>为什么选真实 DB</b>：CAS 的竞争窗口（行锁 + version 比较）无法在 mock 上复现——
 * mock 单线程逐个返回无法暴露并发覆盖；这正是 B 级集成测试相对单测的增量价值。</p>
 */
@DisplayName("B4: 状态机 CAS 并发安全（PENDING→RUNNING）")
class AgentExecutionRecordCasIT extends AbstractItTestBase {

    private static final long TASK_ID = 9301L;
    private static final long AGENT_ID = 9301L;
    private static final long SUB_TASK_ID = 9301L;
    private static final long RECORD_ID = 9301L;

    @Autowired
    private AgentExecutionRecordService agentExecutionRecordService;

    @Test
    @DisplayName("双线程并发 markRunning：恰好一个成功，version 自增 1，终态不可回退")
    void concurrentMarkRunningWinsExactlyOnce() throws Exception {
        seedAssignedSubTask(TASK_ID, AGENT_ID, SUB_TASK_ID);
        String eventId = UUID.randomUUID().toString().replace("-", "");
        seedPendingExecutionRecord(RECORD_ID, eventId, SUB_TASK_ID, AGENT_ID);

        // ---- 并发拨号：两个线程同时 markRunning（对齐起跑线，放大竞争窗口）----
        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<Boolean> f1 = pool.submit(() -> markRunningAligned(ready, start, RECORD_ID));
            Future<Boolean> f2 = pool.submit(() -> markRunningAligned(ready, start, RECORD_ID));
            ready.await(10, TimeUnit.SECONDS);
            start.countDown(); // 同时放行

            boolean r1 = f1.get(10, TimeUnit.SECONDS);
            boolean r2 = f2.get(10, TimeUnit.SECONDS);

            // 恰好一个成功（一胜一负），绝不允许双成功
            assertEquals(1, (r1 ? 1 : 0) + (r2 ? 1 : 0), "并发 CAS 应恰好一个成功");
            // DB 事实：RUNNING（非被覆盖回 PENDING 或损坏）
            assertEquals("RUNNING", recordStatus(RECORD_ID));
            // 乐观锁 version 从 0 → 1（恰一次成功更新）
            assertEquals(1, recordVersion(RECORD_ID), "乐观锁 version 应恰好自增 1");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("终态守卫：RUNNING 不可再次 markRunning，SUCCESS 不可再进终态回退")
    void terminalStateCannotBeReentered() {
        // 独立 id 段 9302（与 concurrent 用例的 9301 区分——2026-09-29 实跑暴露：
        // 类级常量共享导致第二个用例 seed 时 DuplicateKey）
        long taskId = 9302L, agentId = 9302L, subTaskId = 9302L, recordId = 9302L;
        seedAssignedSubTask(taskId, agentId, subTaskId);
        String eventId = UUID.randomUUID().toString().replace("-", "");
        seedPendingExecutionRecord(recordId, eventId, subTaskId, agentId);

        // 第一次成功：PENDING → RUNNING
        assertTrue(agentExecutionRecordService.markRunning(recordId), "首次 PENDING→RUNNING 应成功");
        // 第二次被拒：status 已非 PENDING（双条件 CAS 的 status 守卫）
        assertFalse(agentExecutionRecordService.markRunning(recordId),
                "RUNNING 状态下重复 markRunning 必须被拒绝");
        // 终态推进：RUNNING → SUCCESS（version 1 → 2）
        assertTrue(agentExecutionRecordService.markSuccess(recordId), "RUNNING→SUCCESS 应成功");
        // SUCCESS 是终态：markRunning / markSuccess / markFailed 全部被拒
        assertFalse(agentExecutionRecordService.markRunning(recordId), "SUCCESS 后 markRunning 必须被拒");
        assertFalse(agentExecutionRecordService.markSuccess(recordId), "SUCCESS 后重复 markSuccess 必须被拒");
        assertFalse(agentExecutionRecordService.markFailed(recordId, "late"), "SUCCESS 后 markFailed 必须被拒");
        assertEquals("SUCCESS", recordStatus(recordId));
        assertEquals(2, recordVersion(recordId), "两次终态推进应使 version=2");
    }

    // ==================== 工具 ====================

    /** 线程起跑对齐：ready 计数后等 start 放行，再调 markRunning。 */
    private boolean markRunningAligned(CountDownLatch ready, CountDownLatch start, long recordId) {
        ready.countDown();
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return agentExecutionRecordService.markRunning(recordId);
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
}